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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** 每次定时拉取新闻条数 */
    private static final int FETCH_LIMIT = 50;

    /** 向量相似度检索候选数 */
    private static final int KNN_CANDIDATES = 50;

    private final NewsAdapter newsAdapter;
    private final NewsRepository newsRepository;
    private final ElasticsearchOperations esOperations;
    private final EmbeddingModel embeddingModel;

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
    }

    /**
     * {@inheritDoc}
     * 每30分钟执行一次：拉取50条新闻 → 批量向量化标题 → saveAll() upsert 入ES。
     * 使用批量Embedding接口，50条标题一次请求，严禁循环单条调用。
     */
    @Scheduled(fixedRate = 30 * 60 * 1000L)
    @Override
    public void fetchAndStore() throws NewsApiException {
        log.info("开始拉取新闻，limit={}", FETCH_LIMIT);
        List<NewsDTO> newsList = newsAdapter.fetchLatestNews(FETCH_LIMIT);

        if (newsList.isEmpty()) {
            log.info("本次拉取新闻为空，跳过入库");
            return;
        }

        // 批量向量化：所有标题一次性发给Embedding API，严禁在循环内单条调用
        List<String> titles = newsList.stream()
                .map(NewsDTO::getTitle)
                .collect(Collectors.toList());
        List<float[]> vectors = batchVectorize(titles);

        // 组装 ES 文档
        List<NewsDocument> documents = new ArrayList<>();
        for (int i = 0; i < newsList.size(); i++) {
            NewsDTO dto = newsList.get(i);
            NewsDocument doc = new NewsDocument();
            doc.setId(dto.getId());
            doc.setTitle(dto.getTitle());
            doc.setSummary(dto.getSummary());
            doc.setUrl(dto.getUrl());
            doc.setSource(dto.getSource());
            doc.setCurrencies(dto.getCurrencies());
            doc.setSentiment(dto.getSentiment());
            doc.setPublishedAt(dto.getPublishedAt());
            doc.setCreatedAt(LocalDateTime.now());
            doc.setTitleVector(floatArrayToList(vectors.get(i)));
            documents.add(doc);
        }

        // saveAll() 以 @Id 为键做 upsert，已存在的文档自动覆盖，天然去重
        newsRepository.saveAll(documents);
        log.info("新闻入库完成，共{}条", documents.size());
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

        // Step1: 向量化查询文本
        List<Float> queryVector = floatArrayToList(vectorize(request.getQuery()));

        // Step2: 向量相似度搜索
        List<NewsDocument> vectorResults = vectorSearch(queryVector, symbol, limit);

        // Step3: 关键词搜索
        List<NewsDocument> keywordResults = keywordSearch(request.getQuery(), symbol, limit);

        // Step4: 合并去重，向量结果优先（LinkedHashMap保持插入顺序）
        Map<String, NewsDocument> merged = new LinkedHashMap<>();
        for (NewsDocument doc : vectorResults) {
            merged.put(doc.getId(), doc);
        }
        for (NewsDocument doc : keywordResults) {
            merged.putIfAbsent(doc.getId(), doc);
        }

        // Step5: 截取并转换为DTO
        List<NewsDTO> result = merged.values().stream()
                .limit(limit)
                .map(this::toNewsDTO)
                .collect(Collectors.toList());

        return new NewsSearchResponse(result, result.size());
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
    private List<float[]> batchVectorize(List<String> texts) throws NewsApiException {
        List<float[]> result = new ArrayList<>();
        int batchSize = 25;
        try {
            for (int i = 0; i < texts.size(); i += batchSize) {
                List<String> batch = texts.subList(i, Math.min(i + batchSize, texts.size()));
                var response = embeddingModel.embedForResponse(batch);
                response.getResults().stream()
                        .map(e -> e.getOutput())
                        .forEach(result::add);
            }
            return result;
        } catch (Exception e) {
            throw new NewsApiException("批量Embedding向量化失败, texts.size=" + texts.size(), e);
        }
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
            // 内层过滤查询：有symbol时按currencies过滤，否则matchAll
            var innerQuery = (symbol != null && !symbol.isBlank())
                    ? co.elastic.clients.elasticsearch._types.query_dsl.Query.of(q -> q
                            .term(t -> t.field("currencies").value(symbol)))
                    : co.elastic.clients.elasticsearch._types.query_dsl.Query.of(q -> q
                            .matchAll(m -> m));

            // 此版本 ES Java client 中 Script.Builder 直接持有 source/params，无 inline() 变体
            NativeQuery query = NativeQuery.builder()
                    .withQuery(q -> q.scriptScore(ss -> ss
                            .query(innerQuery)
                            .script(s -> s
                                    .source("cosineSimilarity(params.queryVector, 'titleVector') + 1.0")
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
            NativeQuery query = NativeQuery.builder()
                    .withQuery(q -> q.bool(b -> {
                        b.must(m -> m.match(match -> match.field("title").query(queryText)));
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
