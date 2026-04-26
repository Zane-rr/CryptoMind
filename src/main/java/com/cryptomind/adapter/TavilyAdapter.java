package com.cryptomind.adapter;

import com.cryptomind.dto.NewsDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tavily 实时搜索适配器，提供加密货币领域的实时新闻搜索能力。
 * 调用 POST https://api.tavily.com/search，不持久化，仅在查询时实时返回。
 * 结果以 source="tavily" 标记，区别于 Finnhub 持久化数据。
 * 模块：News
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TavilyAdapter {

    private static final String TAVILY_URL = "https://api.tavily.com/search";

    private static final String SOURCE_TAVILY = "tavily";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${tavily.api-key:}")
    private String apiKey;

    /**
     * 调用 Tavily 搜索接口，返回实时新闻列表。
     * API Key 未配置时直接返回空列表，不报错。
     *
     * @param query      搜索关键词，如 "BTC Bitcoin price"
     * @param maxResults 最大返回条数
     * @return 新闻 DTO 列表，source 固定为 "tavily"
     */
    public List<NewsDTO> search(String query, int maxResults) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Tavily API key 未配置，跳过实时搜索");
            return List.of();
        }
        try {
            // days=1：只返回24小时内的新闻，过滤 Tavily 搜出的历史高相关性旧文章
            String requestBody = objectMapper.writeValueAsString(java.util.Map.of(
                    "api_key",      apiKey,
                    "query",        query,
                    "max_results",  maxResults,
                    "search_depth", "basic",
                    "days",         1
            ));
            Request request = new Request.Builder()
                    .url(TAVILY_URL)
                    .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("Tavily API 返回非200，code={}", response.code());
                    return List.of();
                }
                return parseResults(response.body().string());
            }
        } catch (Exception e) {
            log.warn("Tavily 搜索失败，query={}，原因：{}", query, e.getMessage(), e);
            return List.of();
        }
    }

    // -------------------------------------------------------------------------
    // 私有方法
    // -------------------------------------------------------------------------

    /**
     * 解析 Tavily 响应 JSON，转换为 NewsDTO 列表。
     *
     * @param body 响应体字符串
     * @return NewsDTO 列表
     */
    private List<NewsDTO> parseResults(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode results = root.path("results");
        List<NewsDTO> list = new ArrayList<>();
        for (JsonNode item : results) {
            NewsDTO dto = new NewsDTO();
            String url = item.path("url").asText("");
            dto.setUrl(url);
            // Tavily 无稳定唯一 ID，用 url MD5 对齐 Finnhub 去重策略
            dto.setId("tavily_" + md5Short(url));
            dto.setTitle(item.path("title").asText(""));
            // content 为 Tavily 提取的正文片段，映射到 summary
            dto.setSummary(item.path("content").asText(""));
            dto.setSource(SOURCE_TAVILY);
            dto.setSentiment("neutral");
            dto.setCurrencies(List.of());
            dto.setPublishedAt(parseDate(item.path("published_date").asText("")));
            list.add(dto);
        }
        return list;
    }

    /**
     * 解析 ISO-8601 日期字符串为 LocalDateTime，失败时返回当前时间。
     *
     * @param dateStr Tavily 返回的 published_date，如 "2024-01-15T10:30:00Z"
     * @return LocalDateTime（UTC）
     */
    private LocalDateTime parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            return ZonedDateTime.parse(dateStr).toLocalDateTime();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 取 URL 字符串的8位十六进制 MD5 前缀，用于生成短 ID。
     *
     * @param text 原始字符串
     * @return 8位十六进制字符串
     */
    private String md5Short(String text) {
        if (text.isBlank()) return UUID.randomUUID().toString().substring(0, 8);
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return String.format("%02x%02x%02x%02x", digest[0], digest[1], digest[2], digest[3]);
        } catch (Exception e) {
            return text.substring(0, Math.min(8, text.length()));
        }
    }
}
