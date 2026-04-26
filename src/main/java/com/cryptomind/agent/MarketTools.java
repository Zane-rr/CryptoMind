package com.cryptomind.agent;

import com.cryptomind.dto.NewsDTO;
import com.cryptomind.dto.NewsSearchRequest;
import com.cryptomind.dto.NewsSearchResponse;
import com.cryptomind.service.IMarketService;
import com.cryptomind.service.INewsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

/**
 * Spring AI Tool工具类，提供4个@Tool方法供LLM通过Function Calling自主调用。
 * 注入到 CryptoAgentService 后，通过 chatClient.prompt().tools(marketTools) 注册。
 * 模块：Agent
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketTools {

    private final IMarketService marketService;
    private final INewsService newsService;
    private final ObjectMapper objectMapper;

    /**
     * 查询加密货币实时价格和24小时涨跌幅。
     *
     * @param symbol 交易对，如BTCUSDT
     * @return 价格JSON字符串，或失败提示
     */
    @Tool(description = "获取加密货币实时价格和24小时涨跌幅，输入交易对如BTCUSDT")
    public String getPrice(String symbol) {
        try {
            return objectMapper.writeValueAsString(marketService.getPrice(symbol));
        } catch (Exception e) {
            log.warn("getPrice失败，symbol={}，原因：{}", symbol, e.getMessage());
            return "价格数据获取失败";
        }
    }

    /**
     * 查询加密货币技术指标（RSI、MACD、布林带），固定使用1h周期。
     *
     * @param symbol 交易对，如BTCUSDT
     * @return 技术指标JSON字符串，或失败提示
     */
    @Tool(description = "获取加密货币技术指标，包含RSI、MACD、布林带，输入交易对如BTCUSDT")
    public String getIndicators(String symbol) {
        try {
            return objectMapper.writeValueAsString(
                    marketService.getIndicators(symbol, "1h"));
        } catch (Exception e) {
            log.warn("getIndicators失败，symbol={}，原因：{}", symbol, e.getMessage());
            return "技术指标获取失败";
        }
    }

    /**
     * 搜索加密货币相关新闻，基于ES向量语义检索。
     * 使用英文关键词召回质量更佳。
     *
     * @param keywords 英文搜索关键词，如 Bitcoin BTC crypto
     * @return 格式化新闻列表字符串，或失败提示
     */
    /** 常见加密货币 ticker，用于从关键词中提取 symbol 以优化 Tavily 搜索范围 */
    private static final java.util.Set<String> KNOWN_TICKERS = java.util.Set.of(
            "BTC", "ETH", "SOL", "BNB", "XRP", "DOGE", "ADA", "AVAX", "DOT", "MATIC",
            "LINK", "UNI", "ATOM", "LTC", "TRX", "OP", "ARB", "SUI", "APT", "TON"
    );

    @Tool(description = "搜索加密货币相关新闻，输入英文关键词效果最佳，如Bitcoin BTC crypto")
    public String searchNews(String keywords) {
        try {
            NewsSearchRequest req = new NewsSearchRequest();
            req.setQuery(keywords);
            // 从关键词中提取 ticker，传入 symbol 后 Tavily 会搜索该币种的实时新闻
            req.setSymbol(extractTicker(keywords));
            req.setLimit(15);
            NewsSearchResponse resp = newsService.searchNews(req);
            if (resp.getNews() == null || resp.getNews().isEmpty()) {
                return "暂无相关新闻";
            }
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM/dd HH:mm");
            StringBuilder sb = new StringBuilder();
            int idx = 1;
            for (NewsDTO n : resp.getNews()) {
                String time   = n.getPublishedAt() != null ? n.getPublishedAt().format(fmt) : "";
                String source = n.getSource() != null ? n.getSource() : "unknown";
                // 标题用 markdown 链接格式，前端可渲染为可点击超链接
                String url = n.getUrl() != null && !n.getUrl().isBlank() ? n.getUrl() : "";
                sb.append("[").append(idx++).append("] ");
                if (!url.isBlank()) {
                    sb.append("[").append(n.getTitle()).append("](").append(url).append(")");
                } else {
                    sb.append(n.getTitle());
                }
                if (!time.isBlank()) {
                    sb.append(" (").append(time).append(")");
                }
                sb.append(" [").append(source).append("]");
                sb.append("\n");
                if (n.getSummary() != null && !n.getSummary().isBlank()) {
                    sb.append(n.getSummary()).append("\n");
                }
                sb.append("\n");
            }
            String result = sb.toString().trim();
            log.info("searchNews 工具实际返回内容：\n{}", result);
            return result;
        } catch (Exception e) {
            log.warn("searchNews失败，原因：{}", e.getMessage());
            return "新闻数据获取失败";
        }
    }

    /**
     * 查询账户当前持仓情况。
     * 当前阶段暂返回空列表，Exchange模块完成后实现真实持仓。
     *
     * @param ignored 占位参数，传入空字符串即可
     * @return 持仓JSON字符串，或失败提示
     */
    @Tool(description = "获取币安账户当前持仓情况，无需输入参数，传入空字符串即可")
    public String getPortfolio(String ignored) {
        try {
            return objectMapper.writeValueAsString(marketService.getPortfolio());
        } catch (Exception e) {
            log.warn("getPortfolio失败，原因：{}", e.getMessage());
            return "持仓数据获取失败";
        }
    }

    /**
     * 从关键词字符串中提取第一个已知的加密货币 ticker。
     * 大写匹配，如 "bitcoin BTC price" → "BTC"。
     *
     * @param keywords 搜索关键词
     * @return ticker 字符串，未匹配到时返回 null
     */
    private String extractTicker(String keywords) {
        if (keywords == null || keywords.isBlank()) return null;
        String upper = keywords.toUpperCase();
        for (String ticker : KNOWN_TICKERS) {
            // 整词匹配，避免把 "ATOMIC" 误识别为 "AT"
            if (upper.matches(".*\\b" + ticker + "\\b.*")) {
                return ticker;
            }
        }
        return null;
    }
}
