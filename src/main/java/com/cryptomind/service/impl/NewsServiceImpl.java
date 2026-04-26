package com.cryptomind.service.impl;

import com.cryptomind.adapter.NewsAdapter;
import com.cryptomind.dto.NewsDTO;
import com.cryptomind.dto.NewsSearchRequest;
import com.cryptomind.dto.NewsSearchResponse;
import com.cryptomind.entity.NewsDocument;
import com.cryptomind.exception.NewsApiException;
import com.cryptomind.repository.NewsRepository;
import com.cryptomind.service.INewsService;
import co.elastic.clients.json.JsonData;
import com.cryptomind.adapter.TavilyAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * INewsService 实现，负责新闻拉取、向量化入库和混合检索。
 * 数据流：Finnhub → 批量Embedding → ES upsert（按 url MD5 id去重）。
 * 检索策略：向量语义搜索 + 关键词搜索，结果合并去重后返回。
 * 模块：News
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsServiceImpl implements INewsService {

    /** ES索引名称 */
    private static final String INDEX_NAME = "crypto_news";

    /** 标题向量维度，对应阿里云 text-embedding-v2 输出维度 */
    private static final int VECTOR_DIMS = 1536;

    /** Finnhub 每次拉取条数 */
    private static final int FETCH_LIMIT = 50;

    /** 向量相似度检索候选数 */
    private static final int KNN_CANDIDATES = 50;

    /**
     * 新闻检索时间窗口（小时）。
     * Tavily 每10分钟抓一次，窗口设为12小时可覆盖当前批次并保留上一批兜底。
     */
    private static final int NEWS_MAX_AGE_HOURS = 12;

    /** Tavily 定时拉取条数（入库用，主力数据源） */
    private static final int TAVILY_FETCH_LIMIT = 20;

    /** Tavily 定时拉取通用查询词，覆盖主流币种最新资讯 */
    private static final String TAVILY_FETCH_QUERY = "Bitcoin Ethereum crypto market news latest";

    /** Tavily agent searchNews 每次搜索最大返回条数 */
    private static final int TAVILY_RESULT_LIMIT = 5;

    /** RSS 数据源兜底标识（正常情况下 RssAdapter 会设置具体来源如 coindesk） */
    private static final String SOURCE_RSS = "rss";

    /** 标题指纹 Redis key 前缀，用于跨批次去除 Finnhub 重新分发的旧文章 */
    private static final String TITLE_HASH_KEY_PREFIX = "news:title:hash:";

    /** 标题指纹 TTL 与抓取周期同步（6小时），避免同一批次重复收录 */
    private static final Duration TITLE_HASH_TTL = Duration.ofHours(6);

    private final NewsAdapter newsAdapter;
    private final NewsRepository newsRepository;
    private final ElasticsearchOperations esOperations;
    private final EmbeddingModel embeddingModel;
    private final StringRedisTemplate redisTemplate;
    private final TavilyAdapter tavilyAdapter;

    /**
     * {@inheritDoc}
     * 启动时自动检查并创建 crypto_news 索引，包含 dense_vector 映射。
     * 使用原生 JSON mapping，确保 dims 和 similarity 参数正确写入。
     */
    @PostConstruct
    @Override
    public void buildEsIndex() {
        IndexOperations indexOps = esOperations.indexOps(NewsDocument.class);
        if (indexOps.exists()) {
            log.info("ES索引 {} 已存在，跳过创建", INDEX_NAME);
            return;
        }
        try {
            // 手动指定 dense_vector mapping，确保 dims/index/similarity 参数正确
            Map<String, Object> titleVectorMapping = Map.of(
                    "type", "dense_vector",
                    "dims", VECTOR_DIMS,
                    "index", true,
                    "similarity", "cosine"
            );
            Map<String, Object> properties = new java.util.HashMap<>();
            properties.put("id",          Map.of("type", "keyword"));
            properties.put("title",        Map.of("type", "text"));
            properties.put("summary",      Map.of("type", "text"));
            properties.put("url",          Map.of("type", "keyword"));
            properties.put("source",       Map.of("type", "keyword"));
            properties.put("currencies",   Map.of("type", "keyword"));
            properties.put("sentiment",    Map.of("type", "keyword"));
            properties.put("publishedAt",  Map.of("type", "date"));
            properties.put("titleVector",  titleVectorMapping);
            properties.put("createdAt",    Map.of("type", "date"));

            indexOps.create();
            Map<String, Object> fullMapping = new java.util.HashMap<>();
            fullMapping.put("properties", properties);
            indexOps.putMapping(Document.from(fullMapping));
            log.info("ES索引 {} 创建成功，向量维度={}", INDEX_NAME, VECTOR_DIMS);
        } catch (Exception e) {
            // 索引创建失败不阻断启动，后续写入会报错时再排查
            log.error("ES索引 {} 创建失败，原因：{}", INDEX_NAME, e.getMessage(), e);
        }

        // 启动时异步立即拉取一次，不等10分钟定时任务
        CompletableFuture.runAsync(() -> {
            try {
                fetchAndStore();
            } catch (Exception e) {
                log.warn("启动时新闻初始拉取失败，原因：{}", e.getMessage());
            }
        });
    }

    /**
     * {@inheritDoc}
     * 每10分钟执行一次：Tavily主力（20条）+ Finnhub辅助合并 → 批量向量化 → 清空旧数据写入ES。
     * Tavily 数据时效性强（days=1），优先填入；Finnhub 补充语义覆盖。
     */
    @Scheduled(fixedRate = 10 * 60 * 1000L)
    @Override
    public void fetchAndStore() throws NewsApiException {
        log.info("开始拉取新闻（Tavily + RSS多源）");

        // Step1: Tavily实时拉取（20条，days=1限24h内）
        List<NewsDTO> tavilyList = tavilyAdapter.search(TAVILY_FETCH_QUERY, TAVILY_FETCH_LIMIT);
        log.info("Tavily拉取{}条", tavilyList.size());

        // Step2: RSS多源拉取（CoinDesk + CoinTelegraph + Decrypt，有精确 pubDate）
        List<NewsDTO> rssList = new ArrayList<>();
        try {
            rssList = newsAdapter.fetchLatestNews(FETCH_LIMIT);
            log.info("RSS拉取{}条", rssList.size());
        } catch (Exception e) {
            log.warn("RSS拉取失败，原因：{}", e.getMessage());
        }

        // Step3: 合并，RSS优先（有精确日期），Tavily补充实时内容
        Map<String, NewsDTO> mergedMap = new LinkedHashMap<>();
        for (NewsDTO n : rssList)    mergedMap.put(n.getId(), n);
        for (NewsDTO n : tavilyList) mergedMap.putIfAbsent(n.getId(), n);
        List<NewsDTO> allNews = new ArrayList<>(mergedMap.values());

        if (allNews.isEmpty()) {
            log.info("本次拉取合并后为空，跳过入库");
            return;
        }

        // Step4: 批量向量化所有标题
        List<String> titles = allNews.stream().map(NewsDTO::getTitle).collect(Collectors.toList());
        List<float[]> vectors = batchVectorize(titles);

        // Step5: 组装 ES 文档，向量化失败（null）的条目不跳过，仅不设置向量字段
        // 无向量的文章仍可被关键词搜索命中，保证 ES 始终有数据
        List<NewsDocument> documents = new ArrayList<>();
        int vecFailCount = 0;
        for (int i = 0; i < allNews.size(); i++) {
            float[] vec = vectors.get(i);
            NewsDTO dto = allNews.get(i);
            NewsDocument doc = new NewsDocument();
            doc.setId(dto.getId());
            doc.setTitle(dto.getTitle());
            doc.setSummary(dto.getSummary());
            doc.setUrl(dto.getUrl());
            // 保留原始 source 标记（tavily / coindesk / cointelegraph / decrypt 等）
            doc.setSource(dto.getSource() != null ? dto.getSource() : SOURCE_RSS);
            doc.setCurrencies(dto.getCurrencies());
            doc.setSentiment(dto.getSentiment());
            doc.setPublishedAt(dto.getPublishedAt());
            doc.setCreatedAt(LocalDateTime.now());
            if (vec != null) {
                doc.setTitleVector(floatArrayToList(vec));
            } else {
                vecFailCount++;
            }
            documents.add(doc);
        }
        if (vecFailCount > 0) {
            log.warn("{}条文章向量化失败，将以无向量方式入库（关键词搜索仍可命中）", vecFailCount);
        }

        // Step6: 清空旧数据，写入最新合并批次
        newsRepository.deleteAll();
        newsRepository.saveAll(documents);
        log.info("新闻入库完成，RSS+Tavily共{}条写入ES", documents.size());

        // Step7: Tavily标题指纹写入Redis，防止下次批次重复收录
        markTitlesSeen(tavilyList);
    }

    /**
     * {@inheritDoc}
     * Step1: 向量化查询文本
     * Step2: ES向量相似度搜索（script_score + cosineSimilarity）
     * Step3: ES关键词搜索（match title）
     * Step4: 两个结果按id合并去重，向量结果优先
     * Step5: 截取前limit条，转换为NewsDTO返回
     *
     * @param request 检索请求
     * @return 合并去重后的新闻检索结果
     * @throws NewsApiException 向量化或ES查询失败时抛出
     */
    @Override
    public NewsSearchResponse searchNews(NewsSearchRequest request) throws NewsApiException {
        int limit = request.getLimit() != null ? request.getLimit() : 5;
        String symbol = request.getSymbol() != null ? request.getSymbol().toUpperCase().trim() : null;

        // ES 路径和 Tavily 路径并行执行，互不依赖，任一失败不影响另一路
        CompletableFuture<List<NewsDTO>> esFuture = CompletableFuture.supplyAsync(() -> {
            // 优先向量搜索（语义相关）+ 关键词搜索合并
            // Embedding 失败时降级到纯关键词搜索，保证 ES 结果不为空
            List<NewsDocument> vectorResults = List.of();
            try {
                List<Float> queryVector = floatArrayToList(vectorize(request.getQuery()));
                vectorResults = vectorSearch(queryVector, symbol, limit);
            } catch (Exception e) {
                log.warn("ES向量搜索失败，降级到关键词搜索，原因：{}", e.getMessage());
            }
            try {
                List<NewsDocument> keywordResults = keywordSearch(request.getQuery(), symbol, limit);
                Map<String, NewsDocument> esMap = new LinkedHashMap<>();
                for (NewsDocument doc : vectorResults)  esMap.put(doc.getId(), doc);
                for (NewsDocument doc : keywordResults) esMap.putIfAbsent(doc.getId(), doc);
                return esMap.values().stream().map(this::toNewsDTO).collect(Collectors.toList());
            } catch (Exception e) {
                log.warn("ES关键词搜索失败，原因：{}", e.getMessage());
                return List.of();
            }
        });

        CompletableFuture<List<NewsDTO>> tavilyFuture = CompletableFuture.supplyAsync(() -> {
            String tavilyQuery = symbol != null
                    ? symbol + " " + request.getQuery() + " cryptocurrency news"
                    : request.getQuery() + " crypto market news";
            List<NewsDTO> results = tavilyAdapter.search(tavilyQuery, TAVILY_RESULT_LIMIT);
            log.info("searchNews Tavily返回{}条，query={}", results.size(), tavilyQuery);
            return results;
        });

        List<NewsDTO> esNews     = esFuture.join();
        List<NewsDTO> tavilyNews = tavilyFuture.join();

        // 合并去重：ES（RSS真实文章，有日期）优先，Tavily 补充不足的条数
        // Tavily 泛化查询会返回新闻网站首页，ES 里的 RSS 文章质量更高
        Map<String, NewsDTO> merged = new LinkedHashMap<>();
        for (NewsDTO n : esNews)     merged.put(n.getId(), n);
        for (NewsDTO n : tavilyNews) merged.putIfAbsent(n.getId(), n);

        List<NewsDTO> result = merged.values().stream().limit(limit).collect(Collectors.toList());
        return new NewsSearchResponse(result, result.size());
    }

    /**
     * {@inheritDoc}
     * 直接按 publishedAt 倒序分页查 ES，不走向量搜索，适用于前端新闻面板。
     *
     * @param limit 返回条数
     * @return 按时间倒序的新闻列表
     */
    @Override
    public List<NewsDTO> getLatestNews(int limit) {
        // Tavily 已定时写入 ES，直接查 ES 即可，不再实时调 Tavily（节省额度）
        try {
            NativeQuery query = NativeQuery.builder()
                    .withQuery(q -> q.matchAll(m -> m))
                    .withSort(org.springframework.data.domain.Sort.by(
                            org.springframework.data.domain.Sort.Direction.DESC, "publishedAt"))
                    .withMaxResults(limit)
                    .build();
            SearchHits<NewsDocument> hits = esOperations.search(query, NewsDocument.class);
            List<NewsDTO> result = hits.getSearchHits().stream()
                    .map(SearchHit::getContent)
                    .map(this::toNewsDTO)
                    .collect(Collectors.toList());
            log.info("getLatestNews 返回{}条", result.size());
            return result;
        } catch (Exception e) {
            log.warn("getLatestNews ES查询失败，原因：{}", e.getMessage());
            return List.of();
        }
    }

    // -------------------------------------------------------------------------
    // 私有方法：向量化
    // -------------------------------------------------------------------------

    /**
     * 批量向量化：将标题列表一次性发给阿里云Embedding API，返回对应向量列表。
     * 使用 EmbeddingModel.embedForResponse() 批量接口，严禁在循环内单条调用。
     *
     * @param texts 待向量化的文本列表
     * @return 每条文本对应的 float[] 向量，顺序与输入一致
     * @throws NewsApiException Embedding API调用失败时抛出
     */
//    private List<float[]> batchVectorize(List<String> texts) throws NewsApiException {
//        try {
//            var response = embeddingModel.embedForResponse(texts);
//            return response.getResults().stream()
//                    .map(e -> e.getOutput())
//                    .collect(Collectors.toList());
//        } catch (Exception e) {
//            throw new NewsApiException("批量Embedding向量化失败，texts.size=" + texts.size(), e);
//        }
//    }
    /**
     * 批量向量化，按 batchSize 分批调用 Embedding API。
     * 单批失败时 log.warn 记录失败条数，用 null 占位后继续下一批，不中断整体任务。
     * 返回列表长度与 texts 一致，失败位置为 null，调用方需过滤。
     */
    private List<float[]> batchVectorize(List<String> texts) {
        List<float[]> result = new ArrayList<>(texts.size());
        int batchSize = 25;
        int failCount = 0;
        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> batch = texts.subList(i, Math.min(i + batchSize, texts.size()));
            try {
                var response = embeddingModel.embedForResponse(batch);
                response.getResults().stream()
                        .map(e -> e.getOutput())
                        .forEach(result::add);
            } catch (Exception e) {
                failCount += batch.size();
                log.warn("Embedding批次向量化失败，批次起始idx={}，批次大小={}，原因：{}", i, batch.size(), e.getMessage());
                // 用 null 占位，保证返回列表与 texts 长度一致
                for (int j = 0; j < batch.size(); j++) {
                    result.add(null);
                }
            }
        }
        if (failCount > 0) {
            log.warn("本次向量化共{}条失败，将跳过这些文章入库", failCount);
        }
        return result;
    }

    /**
     * 单文本向量化，用于查询文本的向量化。
     *
     * @param text 待向量化文本
     * @return float[] 向量
     * @throws NewsApiException Embedding API调用失败时抛出
     */
    private float[] vectorize(String text) throws NewsApiException {
        try {
            return embeddingModel.embed(text);
        } catch (Exception e) {
            throw new NewsApiException("查询文本向量化失败", e);
        }
    }

    // -------------------------------------------------------------------------
    // 私有方法：ES检索
    // -------------------------------------------------------------------------

    /**
     * 向量相似度搜索：使用 script_score + cosineSimilarity 函数对 titleVector 字段评分。
     * 若指定 symbol，则先过滤 currencies 字段后再做向量评分。
     *
     * @param queryVector 查询向量
     * @param symbol      币种过滤（可为null）
     * @param limit       返回条数
     * @return 命中的 NewsDocument 列表
     */
    private List<NewsDocument> vectorSearch(List<Float> queryVector, String symbol, int limit) {
        try {
            String cutoff = LocalDateTime.now().minusHours(NEWS_MAX_AGE_HOURS).toString();

            // 内层过滤查询：时间窗口必须满足，可选 currencies 过滤
            var innerQuery = co.elastic.clients.elasticsearch._types.query_dsl.Query.of(q -> q
                    .bool(b -> {
                        b.filter(f -> f.range(r -> r.untyped(u -> u.field("publishedAt").gte(JsonData.of(cutoff)))));
                        if (symbol != null && !symbol.isBlank()) {
                            b.filter(f -> f.term(t -> t.field("currencies").value(symbol)));
                        }
                        return b;
                    }));

            // 此版本 ES Java client 中 Script.Builder 直接持有 source/params，无 inline() 变体
            NativeQuery query = NativeQuery.builder()
                    .withQuery(q -> q.scriptScore(ss -> ss
                            .query(innerQuery)
                            .script(s -> s
                                    .source("doc['titleVector'].size() > 0 ? cosineSimilarity(params.queryVector, 'titleVector') + 1.0 : 1.0")
                                    .params(Map.of("queryVector", JsonData.of(queryVector)))
                            )
                    ))
                    .withMaxResults(limit)
                    .build();

            SearchHits<NewsDocument> hits = esOperations.search(query, NewsDocument.class);
            return hits.getSearchHits().stream()
                    .map(SearchHit::getContent)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("向量检索失败，降级到空结果，原因：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 关键词检索：对 title 字段做 match 查询，可选 currencies term 过滤。
     *
     * @param queryText 查询文本
     * @param symbol    币种过滤（可为null）
     * @param limit     返回条数
     * @return 命中的 NewsDocument 列表
     */
    private List<NewsDocument> keywordSearch(String queryText, String symbol, int limit) {
        try {
            String cutoff = LocalDateTime.now().minusHours(NEWS_MAX_AGE_HOURS).toString();

            NativeQuery query = NativeQuery.builder()
                    .withQuery(q -> q.bool(b -> {
                        b.must(m -> m.match(match -> match.field("title").query(queryText)));
                        b.filter(f -> f.range(r -> r.untyped(u -> u.field("publishedAt").gte(JsonData.of(cutoff)))));
                        if (symbol != null && !symbol.isBlank()) {
                            b.filter(f -> f.term(t -> t.field("currencies").value(symbol)));
                        }
                        return b;
                    }))
                    .withMaxResults(limit)
                    .build();

            SearchHits<NewsDocument> hits = esOperations.search(query, NewsDocument.class);
            return hits.getSearchHits().stream()
                    .map(SearchHit::getContent)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("关键词检索失败，降级到空结果，原因：{}", e.getMessage());
            return List.of();
        }
    }

    // -------------------------------------------------------------------------
    // 私有方法：类型转换
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // 私有方法：标题指纹 Redis 去重
    // -------------------------------------------------------------------------

    /**
     * 检查标题指纹是否已在 Redis 中见过（即该文章已被处理过）。
     * 归一化规则：转小写、去除非字母数字空格字符、合并连续空格。
     *
     * @param title 文章标题
     * @return true = 已见过（重复），false = 首次出现
     */
    private boolean isTitleSeen(String title) {
        if (title == null || title.isBlank()) return false;
        String hash = titleHash(title);
        return Boolean.TRUE.equals(redisTemplate.hasKey(TITLE_HASH_KEY_PREFIX + hash));
    }

    /**
     * 将本批文章的标题指纹批量写入 Redis，TTL = TITLE_HASH_TTL。
     *
     * @param newsList 已成功入库的文章列表
     */
    private void markTitlesSeen(List<NewsDTO> newsList) {
        for (NewsDTO n : newsList) {
            if (n.getTitle() == null || n.getTitle().isBlank()) continue;
            String key = TITLE_HASH_KEY_PREFIX + titleHash(n.getTitle());
            redisTemplate.opsForValue().set(key, "1", TITLE_HASH_TTL);
        }
    }

    /**
     * 对标题归一化后取 MD5，作为内容指纹。
     * 归一化：小写 + 去非字母数字空格 + 合并空格，抹除标点/大小写差异。
     *
     * @param title 原始标题
     * @return MD5 十六进制字符串
     */
    private String titleHash(String title) {
        String normalized = title.toLowerCase()
                .replaceAll("[^a-z0-9 ]", "")
                .replaceAll("\\s+", " ")
                .trim();
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // MD5 在所有 JVM 上均可用，此分支不可达
            return normalized;
        }
    }

    /**
     * 将 float[] 转换为 List<Float>，用于存入 ES dense_vector 字段。
     *
     * @param arr float数组
     * @return List<Float>
     */
    private List<Float> floatArrayToList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) {
            list.add(v);
        }
        return list;
    }

    /**
     * 将 NewsDocument 转换为 NewsDTO，用于 searchNews 返回值。
     *
     * @param doc ES文档
     * @return NewsDTO
     */
    private NewsDTO toNewsDTO(NewsDocument doc) {
        NewsDTO dto = new NewsDTO();
        dto.setId(doc.getId());
        dto.setTitle(doc.getTitle());
        dto.setSummary(doc.getSummary());
        dto.setUrl(doc.getUrl());
        dto.setSource(doc.getSource());
        dto.setCurrencies(doc.getCurrencies());
        dto.setSentiment(doc.getSentiment());
        dto.setPublishedAt(doc.getPublishedAt());
        return dto;
    }
}
