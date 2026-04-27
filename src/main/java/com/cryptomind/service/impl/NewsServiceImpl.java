package com.cryptomind.service.impl;

import com.cryptomind.adapter.NewsAdapter;
import com.cryptomind.adapter.TavilyAdapter;
import com.cryptomind.dto.NewsDTO;
import com.cryptomind.dto.NewsSearchRequest;
import com.cryptomind.dto.NewsSearchResponse;
import com.cryptomind.exception.NewsApiException;
import com.cryptomind.service.INewsService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * INewsService 实现，负责新闻拉取、Redis 缓存和全量返回。
 * 数据流：RSS + Tavily → 合并去重 → Redis（key: news:latest，TTL 12h）。
 * 检索策略：直接返回全量新闻，由上层（LLM）负责相关性过滤。
 * ES 基础设施保留但不再写入，已无向量化依赖。
 * 模块：News
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsServiceImpl implements INewsService {

    /** Redis 全量新闻缓存 key */
    private static final String REDIS_KEY_LATEST_NEWS = "news:latest";

    /** 新闻缓存 TTL，与定时任务周期配套，保留上两批兜底 */
    private static final Duration NEWS_TTL = Duration.ofHours(12);

    /** RSS 每次拉取上限 */
    private static final int FETCH_LIMIT = 50;

    /** Tavily 定时拉取条数 */
    private static final int TAVILY_FETCH_LIMIT = 20;

    /** Tavily 定时拉取通用查询词 */
    private static final String TAVILY_FETCH_QUERY = "Bitcoin Ethereum crypto market news latest";

    /** 标题指纹 Redis key 前缀，用于 Tavily 跨批次去重 */
    private static final String TITLE_HASH_KEY_PREFIX = "news:title:hash:";

    /** 标题指纹 TTL */
    private static final Duration TITLE_HASH_TTL = Duration.ofHours(6);

    private final NewsAdapter newsAdapter;
    private final TavilyAdapter tavilyAdapter;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * {@inheritDoc}
     * ES 索引构建已废弃，启动时异步触发首次新闻拉取，不阻塞启动流程。
     */
    @PostConstruct
    @Override
    public void buildEsIndex() {
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
     * 每10分钟执行：RSS主力 + Tavily补充 → 合并去重 → 序列化写入 Redis。
     * 向量化逻辑已移除，ES 不再写入。
     */
    @Scheduled(fixedRate = 10 * 60 * 1000L)
    @Override
    public void fetchAndStore() throws NewsApiException {
        log.info("开始拉取新闻（Tavily + RSS多源）");

        // Step1: Tavily 实时拉取
        List<NewsDTO> tavilyList = tavilyAdapter.search(TAVILY_FETCH_QUERY, TAVILY_FETCH_LIMIT);
        log.info("Tavily拉取{}条", tavilyList.size());

        // Step2: RSS 多源拉取（CoinDesk / CoinTelegraph / Decrypt）
        List<NewsDTO> rssList = new ArrayList<>();
        try {
            rssList = newsAdapter.fetchLatestNews(FETCH_LIMIT);
            log.info("RSS拉取{}条", rssList.size());
        } catch (Exception e) {
            log.warn("RSS拉取失败，原因：{}", e.getMessage());
        }

        // Step3: 合并去重，RSS 优先（有精确 pubDate）
        Map<String, NewsDTO> mergedMap = new LinkedHashMap<>();
        for (NewsDTO n : rssList)    mergedMap.put(n.getId(), n);
        for (NewsDTO n : tavilyList) mergedMap.putIfAbsent(n.getId(), n);
        List<NewsDTO> allNews = new ArrayList<>(mergedMap.values());

        if (allNews.isEmpty()) {
            log.info("本次拉取合并后为空，跳过写入");
            return;
        }

        // Step4: 序列化写入 Redis，覆盖上一批
        try {
            String json = objectMapper.writeValueAsString(allNews);
            redisTemplate.opsForValue().set(REDIS_KEY_LATEST_NEWS, json, NEWS_TTL);
            log.info("新闻写入Redis完成，共{}条，TTL={}h", allNews.size(), NEWS_TTL.toHours());
        } catch (Exception e) {
            log.error("新闻写入Redis失败，原因：{}", e.getMessage(), e);
        }

        // Step5: Tavily 标题指纹写入 Redis，防止下次批次重复收录
        markTitlesSeen(tavilyList);
    }

    /**
     * {@inheritDoc}
     * 直接返回全量新闻，不做语义过滤，由上层 LLM（searchAndCompressNews）负责相关性判断。
     */
    @Override
    public NewsSearchResponse searchNews(NewsSearchRequest request) throws NewsApiException {
        int limit = request.getLimit() != null ? request.getLimit() : 10;
        List<NewsDTO> news = getLatestNews(limit);
        return new NewsSearchResponse(news, news.size());
    }

    /**
     * {@inheritDoc}
     * 从 Redis 读取全量新闻列表，按写入顺序截取前 limit 条（RSS 优先，已按 pubDate 排序）。
     * Redis 为空时返回空列表，不抛异常，调用方按空结果处理即可。
     *
     * @param limit 返回条数上限
     * @return 新闻列表，Redis 为空时返回空列表
     */
    @Override
    public List<NewsDTO> getLatestNews(int limit) {
        try {
            String json = redisTemplate.opsForValue().get(REDIS_KEY_LATEST_NEWS);
            if (json == null || json.isBlank()) {
                log.warn("Redis中暂无新闻数据（可能正在初始拉取），返回空列表");
                return List.of();
            }
            List<NewsDTO> all = objectMapper.readValue(json, new TypeReference<List<NewsDTO>>() {});
            return all.size() <= limit ? all : all.subList(0, limit);
        } catch (Exception e) {
            log.warn("从Redis读取新闻失败，原因：{}", e.getMessage());
            return List.of();
        }
    }

    // -------------------------------------------------------------------------
    // 私有方法：标题指纹 Redis 去重
    // -------------------------------------------------------------------------

    /**
     * 将本批 Tavily 文章的标题指纹写入 Redis，防止下次批次重复收录。
     *
     * @param newsList Tavily 抓取的新闻列表
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
            return normalized;
        }
    }
}
