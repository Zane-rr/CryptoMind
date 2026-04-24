//package com.cryptomind.adapter;
//
//import com.cryptomind.dto.NewsDTO;
//import com.cryptomind.exception.NewsApiException;
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import okhttp3.OkHttpClient;
//import okhttp3.Request;
//import okhttp3.Response;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.stereotype.Component;
//
//import java.time.LocalDateTime;
//import java.time.format.DateTimeFormatter;
//import java.util.ArrayList;
//import java.util.List;
//
///**
// * CryptoPanic 新闻适配器，实现 NewsAdapter 接口。
// * 调用 CryptoPanic /api/v1/posts/ 接口拉取加密货币新闻。
// * 模块：News
// */
//@Slf4j
//@Component
//@RequiredArgsConstructor
//public class CryptoPanicAdapter implements NewsAdapter {
//
//    private static final String BASE_URL = "https://cryptopanic.com/api/v1/posts/";
//
//    /**
//     * 情绪判断阈值：positive - negative 绝对差值超过此值才认为有明确情绪倾向。
//     * 差值 <= SENTIMENT_THRESHOLD 时归为 neutral。
//     */
//    private static final int SENTIMENT_THRESHOLD = 2;
//
//    private static final String SENTIMENT_POSITIVE = "positive";
//    private static final String SENTIMENT_NEGATIVE = "negative";
//    private static final String SENTIMENT_NEUTRAL   = "neutral";
//
//    /** CryptoPanic ISO 8601 时间格式 */
//    private static final DateTimeFormatter PUBLISHED_AT_FORMATTER =
//            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
//
//    private final OkHttpClient httpClient;
//    private final ObjectMapper objectMapper;
//
//    @Value("${cryptopanic.api-key}")
//    private String apiKey;
//
//    /**
//     * {@inheritDoc}
//     * 调用 GET /api/v1/posts/?auth_token=xxx&limit=N 拉取最新新闻。
//     *
//     * @param limit 最多返回条数，不超过50
//     * @return 新闻DTO列表
//     * @throws NewsApiException HTTP请求或解析失败时抛出
//     */
//    @Override
//    public List<NewsDTO> fetchLatestNews(int limit) throws NewsApiException {
//        String url = BASE_URL + "?auth_token=" + apiKey + "&limit=" + limit + "&kind=news";
//        return fetchAndParse(url);
//    }
//
//    /**
//     * {@inheritDoc}
//     * 调用 GET /api/v1/posts/?currencies=BTC 按币种拉取新闻。
//     *
//     * @param currency 币种代码，如 BTC
//     * @param limit    最多返回条数
//     * @return 新闻DTO列表
//     * @throws NewsApiException HTTP请求或解析失败时抛出
//     */
//    @Override
//    public List<NewsDTO> fetchNewsByCurrency(String currency, int limit) throws NewsApiException {
//        String url = BASE_URL + "?auth_token=" + apiKey
//                + "&currencies=" + currency
//                + "&limit=" + limit
//                + "&kind=news";
//        return fetchAndParse(url);
//    }
//
//    // -------------------------------------------------------------------------
//    // 私有辅助方法
//    // -------------------------------------------------------------------------
//
//    /**
//     * 执行 HTTP GET 请求并将 results 数组解析为 NewsDTO 列表。
//     *
//     * @param url 请求URL
//     * @return NewsDTO列表
//     * @throws NewsApiException 请求失败或解析异常时抛出
//     */
//    private List<NewsDTO> fetchAndParse(String url) throws NewsApiException {
//        String body = executeGet(url);
//        try {
//            JsonNode root = objectMapper.readTree(body);
//            JsonNode results = root.get("results");
//            if (results == null || !results.isArray()) {
//                throw new NewsApiException("CryptoPanic响应格式异常，缺少results字段");
//            }
//
//            List<NewsDTO> newsList = new ArrayList<>();
//            for (JsonNode item : results) {
//                newsList.add(parseNewsItem(item));
//            }
//            return newsList;
//        } catch (NewsApiException e) {
//            throw e;
//        } catch (Exception e) {
//            throw new NewsApiException("解析CryptoPanic响应失败", e);
//        }
//    }
//
//    /**
//     * 将单条新闻JSON节点解析为 NewsDTO。
//     * 情绪计算规则：
//     * - positive - negative > 2  → positive
//     * - negative - positive > 2  → negative
//     * - 差值绝对值 ≤ 2 或votes不存在 → neutral
//     *
//     * @param item 单条新闻JSON节点
//     * @return NewsDTO
//     */
//    private NewsDTO parseNewsItem(JsonNode item) {
//        NewsDTO dto = new NewsDTO();
//
//        dto.setId(item.get("id").asText());
//        dto.setTitle(item.get("title").asText());
//        dto.setUrl(item.get("url").asText());
//
//        // 来源名称
//        JsonNode sourceNode = item.get("source");
//        dto.setSource(sourceNode != null ? sourceNode.get("title").asText() : "unknown");
//
//        // 相关币种代码列表
//        List<String> currencies = new ArrayList<>();
//        JsonNode currenciesNode = item.get("currencies");
//        if (currenciesNode != null && currenciesNode.isArray()) {
//            for (JsonNode c : currenciesNode) {
//                currencies.add(c.get("code").asText());
//            }
//        }
//        dto.setCurrencies(currencies);
//
//        // 情绪计算
//        dto.setSentiment(resolveSentiment(item.get("votes")));
//
//        // 发布时间
//        String publishedAt = item.get("published_at").asText();
//        dto.setPublishedAt(LocalDateTime.parse(publishedAt, PUBLISHED_AT_FORMATTER));
//
//        return dto;
//    }
//
//    /**
//     * 根据 votes 节点计算情绪标签。
//     * 当 votes 不存在或无法解析时，默认返回 neutral，避免抛异常影响主流程。
//     *
//     * @param votes CryptoPanic votes JSON节点
//     * @return positive / negative / neutral
//     */
//    private String resolveSentiment(JsonNode votes) {
//        if (votes == null || votes.isNull()) {
//            return SENTIMENT_NEUTRAL;
//        }
//        try {
//            int positive = votes.path("positive").asInt(0);
//            int negative = votes.path("negative").asInt(0);
//            int diff = positive - negative;
//            if (diff > SENTIMENT_THRESHOLD) {
//                return SENTIMENT_POSITIVE;
//            } else if (diff < -SENTIMENT_THRESHOLD) {
//                return SENTIMENT_NEGATIVE;
//            }
//            return SENTIMENT_NEUTRAL;
//        } catch (Exception e) {
//            log.warn("情绪计算失败，降级为neutral，原因：{}", e.getMessage());
//            return SENTIMENT_NEUTRAL;
//        }
//    }
//
//    /**
//     * 执行无鉴权 GET 请求，返回响应体字符串。
//     *
//     * @param url 请求URL
//     * @return 响应体字符串
//     * @throws NewsApiException HTTP调用失败或状态码非200时抛出
//     */
//    private String executeGet(String url) throws NewsApiException {
//        // API Key 已拼入URL，日志中隐藏，避免泄露
//        log.debug("调用CryptoPanic API，url长度={}", url.length());
//        Request request = new Request.Builder().url(url).build();
//        try (Response response = httpClient.newCall(request).execute()) {
//            if (!response.isSuccessful()) {
//                throw new NewsApiException(
//                        "CryptoPanic API返回非200状态码：" + response.code());
//            }
//            if (response.body() == null) {
//                throw new NewsApiException("CryptoPanic API响应体为空");
//            }
//            return response.body().string();
//        } catch (NewsApiException e) {
//            throw e;
//        } catch (Exception e) {
//            throw new NewsApiException("CryptoPanic网络请求失败", e);
//        }
//    }
//}
