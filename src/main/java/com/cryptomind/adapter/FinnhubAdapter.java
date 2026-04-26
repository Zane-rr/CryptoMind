package com.cryptomind.adapter;

import com.cryptomind.dto.NewsDTO;
import com.cryptomind.exception.NewsApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Finnhub 新闻适配器，实现 NewsAdapter 接口。
 * 调用 GET https://finnhub.io/api/v1/news?category=crypto 拉取加密货币新闻。
 * 鉴权方式：Header X-Finnhub-Token，值来自配置 finnhub.api.key。
 * 去重键：对新闻 url 取 MD5 作为 NewsDTO.id，保证 ES 全局唯一。
 * 模块：News
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FinnhubAdapter implements NewsAdapter {

    private static final String NEWS_URL = "https://finnhub.io/api/v1/news?category=crypto";

    private static final String HEADER_TOKEN = "X-Finnhub-Token";

    /** Finnhub 数据暂不提供情绪评分，统一默认 neutral */
    private static final String DEFAULT_SENTIMENT = "neutral";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${finnhub.api.key}")
    private String apiKey;

    /**
     * {@inheritDoc}
     * 调用 Finnhub GET /api/v1/news?category=crypto 拉取全量加密货币新闻，
     * 截取前 limit 条返回。
     *
     * @param limit 最多返回条数，不超过50
     * @return 新闻DTO列表
     * @throws NewsApiException HTTP请求或JSON解析失败时抛出
     */
    @Override
    public List<NewsDTO> fetchLatestNews(int limit) throws NewsApiException {
        log.info("调用Finnhub API拉取加密货币新闻，limit={}", limit);
        String body = executeGet(NEWS_URL);
        List<NewsDTO> all = parseNewsArray(body);
        // Finnhub 返回条数不固定，按需截取
        return all.size() <= limit ? all : all.subList(0, limit);
    }

    /**
     * {@inheritDoc}
     * 调用 Finnhub GET /api/v1/news?category=crypto 拉取全量，
     * 然后按 related 字段或标题过滤出与指定币种相关的新闻。
     *
     * @param currency 币种代码，如 BTC
     * @param limit    最多返回条数
     * @return 新闻DTO列表
     * @throws NewsApiException HTTP请求或JSON解析失败时抛出
     */
    @Override
    public List<NewsDTO> fetchNewsByCurrency(String currency, int limit) throws NewsApiException {
        log.info("调用Finnhub API按币种拉取新闻，currency={}，limit={}", currency, limit);
        String body = executeGet(NEWS_URL);
        List<NewsDTO> all = parseNewsArray(body);

        // Finnhub general news 无服务端币种过滤参数，在客户端按 title/source 关键词过滤
        String keyword = currency.toUpperCase();
        List<NewsDTO> filtered = new ArrayList<>();
        for (NewsDTO dto : all) {
            if (filtered.size() >= limit) {
                break;
            }
            String title = dto.getTitle() != null ? dto.getTitle().toUpperCase() : "";
            String source = dto.getSource() != null ? dto.getSource().toUpperCase() : "";
            if (title.contains(keyword) || source.contains(keyword)) {
                filtered.add(dto);
            }
        }
        return filtered;
    }

    // -------------------------------------------------------------------------
    // 私有辅助方法
    // -------------------------------------------------------------------------

    /**
     * 将 Finnhub 响应的 JSON 数组解析为 NewsDTO 列表。
     *
     * @param body 响应体字符串（JSON 数组）
     * @return NewsDTO列表
     * @throws NewsApiException JSON解析失败时抛出
     */
    private List<NewsDTO> parseNewsArray(String body) throws NewsApiException {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!root.isArray()) {
                throw new NewsApiException("Finnhub响应格式异常，期望JSON数组，实际：" + root.getNodeType());
            }
            List<NewsDTO> result = new ArrayList<>();
            for (JsonNode item : root) {
                result.add(parseNewsItem(item));
            }
            return result;
        } catch (NewsApiException e) {
            throw e;
        } catch (Exception e) {
            throw new NewsApiException("解析Finnhub新闻响应失败", e);
        }
    }

    /**
     * 将单条新闻 JSON 节点解析为 NewsDTO。
     * 字段映射：
     * - headline  → title
     * - summary   → summary
     * - url MD5   → id（全局去重键）
     * - source    → source
     * - datetime  → publishedAt（UNIX秒级时间戳转LocalDateTime）
     * - sentiment → 固定为 "neutral"
     *
     * @param item 单条新闻JSON节点
     * @return NewsDTO
     */
    private NewsDTO parseNewsItem(JsonNode item) {
        NewsDTO dto = new NewsDTO();

        String url = item.path("url").asText("");
        dto.setUrl(url);
        // 以 url 的 MD5 作为全局唯一去重键
        dto.setId(md5(url));

        dto.setTitle(item.path("headline").asText(""));
        dto.setSummary(item.path("summary").asText(""));
        dto.setSource(item.path("source").asText("unknown"));
        dto.setSentiment(DEFAULT_SENTIMENT);

        // Finnhub datetime 为 UNIX 秒级时间戳
        long epochSeconds = item.path("datetime").asLong(0L);
        dto.setPublishedAt(epochSeconds > 0
                ? LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC)
                : LocalDateTime.now(ZoneOffset.UTC));

        // Finnhub related 字段为逗号分隔的相关标的，解析为 currencies 列表
        String related = item.path("related").asText("");
        List<String> currencies = new ArrayList<>();
        if (!related.isBlank()) {
            for (String sym : related.split(",")) {
                String trimmed = sym.trim();
                if (!trimmed.isEmpty()) {
                    currencies.add(trimmed.toUpperCase());
                }
            }
        }
        dto.setCurrencies(currencies);

        return dto;
    }

    /**
     * 对字符串取 MD5 十六进制摘要，用于 url → id 的去重键生成。
     * 若 url 为空，返回空字符串。
     *
     * @param text 原始字符串
     * @return MD5十六进制字符串（小写）
     */
    private String md5(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            // MD5 在所有JVM上均支持，此分支理论上不可达
            log.warn("MD5计算失败，降级使用原始url作为id，url长度={}", text.length());
            return text;
        }
    }

    /**
     * 携带 Header 鉴权执行 GET 请求，返回响应体字符串。
     *
     * @param url 请求URL
     * @return 响应体字符串
     * @throws NewsApiException HTTP调用失败或状态码非2xx时抛出
     */
    private String executeGet(String url) throws NewsApiException {
        // API Key 通过 Header 传入，不拼入URL，日志安全
        log.debug("调用Finnhub API，url={}", url);
        Request request = new Request.Builder()
                .url(url)
                .header(HEADER_TOKEN, apiKey)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new NewsApiException("Finnhub API返回非200状态码：" + response.code());
            }
            if (response.body() == null) {
                throw new NewsApiException("Finnhub API响应体为空");
            }
            return response.body().string();
        } catch (NewsApiException e) {
            throw e;
        } catch (Exception e) {
            throw new NewsApiException("Finnhub网络请求失败", e);
        }
    }
}
