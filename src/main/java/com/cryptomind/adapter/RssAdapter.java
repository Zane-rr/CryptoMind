package com.cryptomind.adapter;

import com.cryptomind.dto.NewsDTO;
import com.cryptomind.exception.NewsApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * RSS 多源新闻适配器，实现 NewsAdapter 接口，替换 Finnhub 作为主力新闻源。
 * 聚合 CoinDesk、CoinTelegraph、Decrypt 三个 RSS feed，文章发布时间来自 &lt;pubDate&gt; 字段，精确可靠。
 * 不依赖外部 API Key，完全免费，无额度限制。
 * 模块：News
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class RssAdapter implements NewsAdapter {

    /** RSS 数据源列表，格式：{feedUrl, sourceName} */
    private static final List<String[]> RSS_FEEDS = List.of(
            new String[]{"https://www.coindesk.com/arc/outboundfeeds/rss/", "coindesk"},
            new String[]{"https://cointelegraph.com/rss",                   "cointelegraph"},
            new String[]{"https://decrypt.co/feed",                         "decrypt"}
    );

    /** RSS pubDate 标准格式：RFC 2822，如 "Mon, 26 Apr 2026 10:30:00 GMT" */
    private static final String PUB_DATE_PATTERN = "EEE, dd MMM yyyy HH:mm:ss zzz";

    private static final String DEFAULT_SENTIMENT = "neutral";

    private final OkHttpClient httpClient;

    /**
     * {@inheritDoc}
     * 并行拉取所有 RSS 源，按 publishedAt 倒序排列后截取 limit 条。
     * 单个 RSS 源拉取失败时 log.warn 记录后跳过，不影响其他源。
     *
     * @param limit 最多返回条数
     * @return 按发布时间倒序的新闻列表
     * @throws NewsApiException 所有数据源均失败时抛出
     */
    @Override
    public List<NewsDTO> fetchLatestNews(int limit) throws NewsApiException {
        List<NewsDTO> all = new ArrayList<>();
        for (String[] feed : RSS_FEEDS) {
            String feedUrl    = feed[0];
            String sourceName = feed[1];
            try {
                List<NewsDTO> items = fetchFeed(feedUrl, sourceName);
                log.info("RSS拉取成功，source={}，条数={}", sourceName, items.size());
                all.addAll(items);
            } catch (Exception e) {
                log.warn("RSS拉取失败，source={}，原因：{}", sourceName, e.getMessage());
            }
        }

        if (all.isEmpty()) {
            throw new NewsApiException("所有RSS数据源均拉取失败");
        }

        // 按 publishedAt 倒序，null 排最后
        all.sort((a, b) -> {
            if (a.getPublishedAt() == null) return 1;
            if (b.getPublishedAt() == null) return -1;
            return b.getPublishedAt().compareTo(a.getPublishedAt());
        });

        return all.size() <= limit ? all : all.subList(0, limit);
    }

    /**
     * {@inheritDoc}
     * 拉取全量后按标题关键词过滤出与指定币种相关的文章。
     *
     * @param currency 币种代码，如 BTC
     * @param limit    最多返回条数
     * @return 与币种相关的新闻列表
     * @throws NewsApiException 数据源请求失败时抛出
     */
    @Override
    public List<NewsDTO> fetchNewsByCurrency(String currency, int limit) throws NewsApiException {
        String keyword = currency.toUpperCase();
        // 多拉取几倍以保证过滤后有足够条数
        List<NewsDTO> all = fetchLatestNews(limit * 5);
        return all.stream()
                .filter(n -> n.getTitle() != null && n.getTitle().toUpperCase().contains(keyword))
                .limit(limit)
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // 私有方法
    // -------------------------------------------------------------------------

    /**
     * 拉取单个 RSS feed 并解析返回 NewsDTO 列表。
     *
     * @param feedUrl    RSS feed URL
     * @param sourceName 数据源名称，如 coindesk
     * @return 新闻列表
     * @throws Exception HTTP 请求或 XML 解析失败时抛出
     */
    private List<NewsDTO> fetchFeed(String feedUrl, String sourceName) throws Exception {
        Request request = new Request.Builder().url(feedUrl).build();
        String xmlBody;
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new Exception("HTTP " + response.code());
            }
            xmlBody = response.body().string();
        }
        return parseRss(xmlBody, sourceName);
    }

    /**
     * 解析 RSS XML 字符串，提取所有 &lt;item&gt; 节点转为 NewsDTO。
     *
     * @param xml        RSS XML 字符串
     * @param sourceName 数据源名称
     * @return NewsDTO 列表
     * @throws Exception XML 解析失败时抛出
     */
    private List<NewsDTO> parseRss(String xml, String sourceName) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // 禁用外部实体，防止 XXE 注入
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xml)));

        NodeList items = doc.getElementsByTagName("item");
        List<NewsDTO> result = new ArrayList<>();
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            result.add(parseItem(item, sourceName));
        }
        return result;
    }

    /**
     * 将单个 &lt;item&gt; XML 元素转为 NewsDTO。
     * description 字段可能含 HTML 标签，使用正则剥离。
     *
     * @param item       RSS &lt;item&gt; DOM 元素
     * @param sourceName 数据源名称
     * @return NewsDTO
     */
    private NewsDTO parseItem(Element item, String sourceName) {
        NewsDTO dto = new NewsDTO();

        String url = getTagText(item, "link");
        dto.setUrl(url);
        dto.setId(sourceName + "_" + md5Short(url));
        dto.setTitle(getTagText(item, "title"));

        // description 可能含 HTML，剥离标签后作为摘要
        String desc = getTagText(item, "description");
        dto.setSummary(desc.replaceAll("<[^>]+>", "").trim());

        dto.setSource(sourceName);
        dto.setSentiment(DEFAULT_SENTIMENT);
        dto.setCurrencies(List.of());
        dto.setPublishedAt(parsePubDate(getTagText(item, "pubDate")));

        return dto;
    }

    /**
     * 解析 RFC 2822 格式的 pubDate 字符串为 LocalDateTime（UTC）。
     * 解析失败返回 null，由调用方决定如何处理。
     *
     * @param pubDateStr RSS &lt;pubDate&gt; 字符串，如 "Mon, 26 Apr 2026 10:30:00 GMT"
     * @return LocalDateTime（UTC），解析失败时返回 null
     */
    private LocalDateTime parsePubDate(String pubDateStr) {
        if (pubDateStr == null || pubDateStr.isBlank()) return null;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat(PUB_DATE_PATTERN, Locale.ENGLISH);
            Date date = sdf.parse(pubDateStr.trim());
            return date.toInstant().atZone(ZoneOffset.UTC).toLocalDateTime();
        } catch (Exception e) {
            log.debug("pubDate解析失败，原始值={}，原因：{}", pubDateStr, e.getMessage());
            return null;
        }
    }

    /**
     * 获取 XML 元素下第一个匹配 tagName 的文本内容，不存在返回空字符串。
     *
     * @param parent  父元素
     * @param tagName 标签名
     * @return 文本内容
     */
    private String getTagText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) return "";
        return nodes.item(0).getTextContent().trim();
    }

    /**
     * 取 URL 字符串的 MD5 前8位十六进制，用于生成短 ID。
     *
     * @param text 原始字符串
     * @return 8位十六进制字符串
     */
    private String md5Short(String text) {
        if (text == null || text.isBlank()) return String.valueOf(System.currentTimeMillis());
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return String.format("%02x%02x%02x%02x", digest[0], digest[1], digest[2], digest[3]);
        } catch (Exception e) {
            return text.substring(0, Math.min(8, text.length()));
        }
    }
}
