package com.cryptomind.agent;

import com.cryptomind.dto.NewsDTO;
import com.cryptomind.service.IMarketService;
import com.cryptomind.service.INewsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Spring AI Tool工具类，提供4个@Tool方法供LLM通过Function Calling自主调用。
 * 注入到 CryptoAgentService 后，通过 chatClient.prompt().tools(marketTools) 注册。
 * 模块：Agent
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketTools {

    private static final String REDIS_KEY_FILTERED_NEWS = "news:filtered:";
    private static final Duration FILTERED_NEWS_TTL = Duration.ofMinutes(10);

    private final IMarketService marketService;
    private final INewsService newsService;
    private final ChatClient chatClient;
    private final StringRedisTemplate redisTemplate;
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
    /**
     * 从 Redis 取全量新闻，调用 LLM 筛选与用户问题最相关的5-8条并压缩为摘要返回。
     * 结果缓存 Redis 10分钟（key: news:filtered:{symbol}:{questionMD5}），相同问题复用缓存跳过 LLM。
     *
     * @param question 用户问题或搜索关键词（中英文均可）
     * @return 格式化的筛选新闻摘要，或失败提示
     */
    @Tool(description = "搜索并筛选与用户问题最相关的加密货币新闻，输入用户问题或关键词")
    public String searchAndCompressNews(String question) {
        try {
            // 缓存 key 用问题 MD5，避免 key 过长
            String cacheKey = REDIS_KEY_FILTERED_NEWS + md5Short(question);
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                log.info("searchAndCompressNews 命中缓存，question={}", question);
                return cached;
            }

            // 从 Redis 取全量新闻
            List<NewsDTO> allNews = newsService.getLatestNews(60);
            if (allNews.isEmpty()) {
                return "暂无相关新闻数据";
            }

            // 将全量新闻格式化为 LLM 可读的文本
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM/dd HH:mm");
            StringBuilder newsList = new StringBuilder();
            int idx = 1;
            for (NewsDTO n : allNews) {
                String time = n.getPublishedAt() != null ? n.getPublishedAt().format(fmt) : "";
                newsList.append("[").append(idx++).append("] ")
                        .append(n.getTitle());
                if (!time.isBlank()) newsList.append(" (").append(time).append(")");
                if (n.getSummary() != null && !n.getSummary().isBlank()) {
                    newsList.append("\n").append(n.getSummary());
                }
                newsList.append("\n\n");
            }

            // LLM 筛选并压缩
            String prompt = "以下是最新加密货币新闻列表：\n\n" + newsList +
                    "\n用户问题：" + question +
                    "\n\n请从以上新闻中筛选最相关的5-8条，每条压缩为一句话摘要（保留原标题和时间）。" +
                    "格式：[序号] 标题 (时间)\n一句话摘要\n\n" +
                    "只输出筛选结果，不要解释，不相关的新闻直接忽略。";

            String result = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (result == null || result.isBlank()) {
                return "暂无相关新闻";
            }

            // 写入缓存
            redisTemplate.opsForValue().set(cacheKey, result, FILTERED_NEWS_TTL);
            log.info("searchAndCompressNews 完成，question={}，结果长度={}", question, result.length());
            return result;
        } catch (Exception e) {
            log.warn("searchAndCompressNews失败，原因：{}", e.getMessage());
            return "新闻数据获取失败";
        }
    }

    /**
     * 取字符串 MD5 的前16位，用于构造缓存 key。
     */
    private String md5Short(String text) {
        if (text == null || text.isBlank()) return "empty";
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString().substring(0, 16);
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
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

}
