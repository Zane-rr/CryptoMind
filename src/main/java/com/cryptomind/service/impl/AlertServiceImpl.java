package com.cryptomind.service.impl;

import com.cryptomind.adapter.ExchangeAdapter;
import com.cryptomind.dto.IndicatorResponse;
import com.cryptomind.dto.PositionData;
import com.cryptomind.dto.PriceResponse;
import com.cryptomind.entity.AlertHistory;
import com.cryptomind.entity.AlertRule;
import com.cryptomind.repository.AlertHistoryRepository;
import com.cryptomind.repository.AlertRuleRepository;
import com.cryptomind.service.IAlertService;
import com.cryptomind.service.IMarketService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Base64;

/**
 * Alert 模块核心实现。
 * 定时任务逻辑：
 *   1. 合并持仓路径（getPortfolio 过滤稳定币）和自选路径（AlertRule 表）的 symbol 集合
 *   2. 对每个 symbol 拉取价格 + 技术指标，检查四条规则
 *   3. 触发时先查 Redis 冷却 key，未冷却则：LLM 生成研判文案 → 推送钉钉 → 写 AlertHistory → 设 Redis TTL
 * 模块：Alert
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertServiceImpl implements IAlertService {

    // -------------------------------------------------------------------------
    // 规则常量
    // -------------------------------------------------------------------------

    /** 5分钟K线涨跌幅触发阈值（%），超过此值触发价格类预警 */
    private static final double PRICE_CHANGE_THRESHOLD = 3.0;

    /** RSI超买阈值 */
    private static final double RSI_OVERBOUGHT = 70.0;

    /** RSI超卖阈值 */
    private static final double RSI_OVERSOLD = 30.0;

    /** 价格类规则冷却时间 */
    private static final Duration PRICE_COOLDOWN = Duration.ofMinutes(30);

    /** RSI类规则冷却时间 */
    private static final Duration RSI_COOLDOWN = Duration.ofHours(2);

    /** Redis 冷却 key 前缀 */
    private static final String COOLDOWN_KEY_PREFIX = "alert:cooldown:";

    /** 稳定币 symbol 集合，持仓路径跳过这些 */
    private static final Set<String> STABLECOINS = Set.of(
            "USDT", "BUSD", "USDC", "DAI", "TUSD", "FDUSD"
    );

    /** 规则类型常量 */
    private static final String RULE_PRICE_UP       = "PRICE_UP";
    private static final String RULE_PRICE_DOWN     = "PRICE_DOWN";
    private static final String RULE_RSI_OB         = "RSI_OVERBOUGHT";
    private static final String RULE_RSI_OS         = "RSI_OVERSOLD";

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    // -------------------------------------------------------------------------
    // 依赖注入
    // -------------------------------------------------------------------------

    private final AlertRuleRepository alertRuleRepository;
    private final AlertHistoryRepository alertHistoryRepository;
    private final IMarketService marketService;
    private final ExchangeAdapter exchangeAdapter;
    private final StringRedisTemplate redisTemplate;
    private final ChatClient chatClient;
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    @Value("${dingtalk.webhook.url:}")
    private String dingTalkWebhookUrl;

    /** 加签密钥（SEC...），配置后自动在请求中附加 timestamp + sign 参数 */
    @Value("${dingtalk.secret:}")
    private String dingTalkSecret;

    // -------------------------------------------------------------------------
    // IAlertService 实现
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     * 添加前调用 Binance getPrice 验证 symbol 是否存在，不存在时抛出 IllegalArgumentException。
     * 重复 symbol 时直接返回已存在记录，不抛异常。
     */
    @Override
    public AlertRule addWatchlist(String symbol) {
        String normalized = symbol.toUpperCase().trim();

        // 验证 symbol 在 Binance 上是否存在
        try {
            marketService.getPrice(normalized);
        } catch (Exception e) {
            throw new IllegalArgumentException("币种 " + normalized + " 不存在或暂不支持");
        }

        return alertRuleRepository.findBySymbol(normalized).orElseGet(() -> {
            AlertRule rule = new AlertRule();
            rule.setSymbol(normalized);
            AlertRule saved = alertRuleRepository.save(rule);
            log.info("已添加自选监控：{}", normalized);
            return saved;
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<AlertRule> listWatchlist() {
        return alertRuleRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeWatchlist(Long id) {
        alertRuleRepository.deleteById(id);
        log.info("已删除自选监控，id={}", id);
    }

    /**
     * {@inheritDoc}
     * 定时任务入口，同时暴露为手动触发接口。
     * fixedRateString 从配置读取，默认5分钟。
     */
    @Scheduled(fixedRateString = "${crypto.alert.check-interval:300000}")
    @Override
    public void checkAlerts() {
        log.info("开始预警检查...");

        // Step1: 收集所有需要检查的 symbol
        Set<String> symbols = collectSymbols();
        if (symbols.isEmpty()) {
            log.info("无监控 symbol，跳过本次检查");
            return;
        }
        log.info("本次检查 symbol 列表：{}", symbols);

        // Step2: 逐个检查
        for (String symbol : symbols) {
            try {
                checkSymbol(symbol);
            } catch (Exception e) {
                // 单个 symbol 检查失败不影响其他 symbol
                log.warn("symbol={} 检查异常，已跳过，原因：{}", symbol, e.getMessage());
            }
        }
        log.info("预警检查完成，共检查{}个symbol", symbols.size());
    }

    // -------------------------------------------------------------------------
    // 私有方法：symbol 收集
    // -------------------------------------------------------------------------

    /**
     * 合并持仓路径（过滤稳定币）和自选路径，返回去重后的 symbol 集合。
     *
     * @return 需要检查的 symbol 集合
     */
    private Set<String> collectSymbols() {
        Set<String> symbols = new LinkedHashSet<>();

        // 持仓路径
        try {
            List<PositionData> portfolio = marketService.getPortfolio();
            for (PositionData pos : portfolio) {
                String asset = pos.getAsset();
                if (asset != null && !STABLECOINS.contains(asset.toUpperCase())) {
                    symbols.add(asset.toUpperCase() + "USDT");
                }
            }
        } catch (Exception e) {
            log.warn("持仓路径获取失败，已跳过，原因：{}", e.getMessage());
        }

        // 自选路径
        alertRuleRepository.findAll().forEach(rule -> symbols.add(rule.getSymbol()));

        return symbols;
    }

    // -------------------------------------------------------------------------
    // 私有方法：单 symbol 检查
    // -------------------------------------------------------------------------

    /**
     * 对指定 symbol 拉取行情数据并检查全部四条规则。
     *
     * @param symbol 交易对，如 BTCUSDT
     */
    private void checkSymbol(String symbol) {
        PriceResponse price;
        IndicatorResponse indicators;
        try {
            price      = marketService.getPrice(symbol);
            indicators = marketService.getIndicators(symbol, "1h");
        } catch (Exception e) {
            log.warn("symbol={} 数据获取失败，原因：{}", symbol, e.getMessage());
            return;
        }

        double priceVal  = price.getLastPrice() != null ? price.getLastPrice().doubleValue() : 0;
        double change24h = price.getPriceChangePercent() != null ? price.getPriceChangePercent().doubleValue() : 0;
        double rsi       = indicators.getRsi() != null ? indicators.getRsi().doubleValue() : 50;

        // 规则1/2：拉最新5分钟K线计算涨跌幅
        Double change5m = get5mChange(symbol);
        if (change5m != null) {
            if (change5m > PRICE_CHANGE_THRESHOLD) {
                maybeAlert(symbol, RULE_PRICE_UP, priceVal, rsi, change5m, PRICE_COOLDOWN);
            }
            if (change5m < -PRICE_CHANGE_THRESHOLD) {
                maybeAlert(symbol, RULE_PRICE_DOWN, priceVal, rsi, change5m, PRICE_COOLDOWN);
            }
        }

        // 规则3：RSI超买
        if (rsi > RSI_OVERBOUGHT) {
            maybeAlert(symbol, RULE_RSI_OB, priceVal, rsi, change24h, RSI_COOLDOWN);
        }
        // 规则4：RSI超卖
        if (rsi < RSI_OVERSOLD) {
            maybeAlert(symbol, RULE_RSI_OS, priceVal, rsi, change24h, RSI_COOLDOWN);
        }
    }

    /**
     * 拉取最新一根5分钟K线，计算 (close - open) / open * 100 作为5分钟涨跌幅。
     * 获取失败时返回 null，不阻断其他规则检查。
     *
     * @param symbol 交易对
     * @return 5分钟涨跌幅（%），获取失败返回 null
     */
    private Double get5mChange(String symbol) {
        try {
            // limit=2：拿最后2根，取最新一根（index=1）可能正在形成中；取 index=0 是上一根已收盘
            List<java.math.BigDecimal[]> klines = exchangeAdapter.getKlines(symbol, "5m", 2);
            if (klines == null || klines.isEmpty()) return null;
            // 使用最后一根（当前形成中的5m蜡烛）反映最新变动
            java.math.BigDecimal[] bar = klines.get(klines.size() - 1);
            double open  = bar[1].doubleValue();
            double close = bar[4].doubleValue();
            if (open == 0) return null;
            return (close - open) / open * 100;
        } catch (Exception e) {
            log.warn("symbol={} 获取5分钟K线失败，原因：{}", symbol, e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // 私有方法：冷却检查 + 触发推送
    // -------------------------------------------------------------------------

    /**
     * 检查冷却，通过则执行推送全流程。
     *
     * @param symbol        交易对
     * @param ruleType      规则类型
     * @param price         当前价格
     * @param rsi           当前RSI
     * @param changePercent 24h涨跌幅
     * @param cooldown      冷却时长
     */
    private void maybeAlert(String symbol, String ruleType, double price, double rsi,
                            double changePercent, Duration cooldown) {
        String cooldownKey = COOLDOWN_KEY_PREFIX + symbol + ":" + ruleType;

        // Redis 冷却检查
        if (Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey))) {
            log.info("symbol={} ruleType={} 冷却中（Redis），跳过推送", symbol, ruleType);
            return;
        }

        // Redis 重启兜底：检查 DB 中是否有最近一次在冷却期内的记录
        LocalDateTime cooldownSince = LocalDateTime.now().minus(cooldown);
        boolean recentExists = alertHistoryRepository
                .findTopBySymbolAndRuleTypeAndTriggeredAtAfterOrderByTriggeredAtDesc(
                        symbol, ruleType, cooldownSince)
                .isPresent();
        if (recentExists) {
            log.info("symbol={} ruleType={} 冷却中（DB兜底），跳过推送", symbol, ruleType);
            // 顺手补设 Redis key，避免下次再查 DB
            redisTemplate.opsForValue().set(cooldownKey, "1",
                    cooldown.minus(Duration.between(cooldownSince, LocalDateTime.now())));
            return;
        }

        // 触发推送
        log.info("预警触发：symbol={} ruleType={} price={} rsi={} change={}%",
                symbol, ruleType, price, rsi, changePercent);

        String llmCopy = generateLLMCopy(symbol, ruleType, price, rsi, changePercent);
        String message = buildDingTalkMessage(symbol, ruleType, price, rsi, changePercent, llmCopy);
        pushDingTalk(message);

        // 写 AlertHistory
        AlertHistory history = new AlertHistory();
        history.setSymbol(symbol);
        history.setRuleType(ruleType);
        history.setPrice(price);
        history.setRsiValue(rsi);
        history.setPriceChangePercent(changePercent);
        history.setLlmCopy(llmCopy);
        alertHistoryRepository.save(history);

        // 设置 Redis 冷却
        redisTemplate.opsForValue().set(cooldownKey, "1", cooldown);
        log.info("预警推送完成，冷却{}，symbol={} ruleType={}", cooldown, symbol, ruleType);
    }

    // -------------------------------------------------------------------------
    // 私有方法：LLM 文案生成
    // -------------------------------------------------------------------------

    /**
     * 调用 LLM 生成 2-3 句话的研判文案，失败时返回空字符串（不阻断推送）。
     *
     * @param symbol        交易对
     * @param ruleType      规则类型
     * @param price         当前价格
     * @param rsi           当前RSI值
     * @param changePercent 24h涨跌幅
     * @return 研判文案，失败时为空字符串
     */
    private String generateLLMCopy(String symbol, String ruleType, double price,
                                   double rsi, double changePercent) {
        try {
            String coin = symbol.replace("USDT", "");
            String trigger = switch (ruleType) {
                case RULE_PRICE_UP   -> String.format("5分钟涨幅达到+%.2f%%", changePercent);
                case RULE_PRICE_DOWN -> String.format("5分钟跌幅达到%.2f%%", changePercent);
                case RULE_RSI_OB     -> String.format("RSI升至%.1f，进入超买区间", rsi);
                case RULE_RSI_OS     -> String.format("RSI降至%.1f，进入超卖区间", rsi);
                default              -> "触发预警规则";
            };
            String prompt = String.format(
                    "%s 当前价格 %.2f USDT，%s，RSI(1h)=%.1f。\n" +
                    "请用2-3句话给出专业研判，包括：当前信号含义、短期操作建议（含具体价位）。" +
                    "直接给结论，不要开场白，不要称呼用户。",
                    coin, price, trigger, rsi);

            String result = chatClient.prompt()
                    .system("你是专业的加密货币分析助手，给出简洁专业的市场研判。")
                    .user(prompt)
                    .call()
                    .content();
            return result != null ? result.trim() : "";
        } catch (Exception e) {
            log.warn("LLM研判文案生成失败，symbol={}，原因：{}", symbol, e.getMessage());
            return "";
        }
    }

    // -------------------------------------------------------------------------
    // 私有方法：钉钉推送
    // -------------------------------------------------------------------------

    /**
     * 构建钉钉 Markdown 消息内容。
     *
     * @param symbol        交易对
     * @param ruleType      规则类型
     * @param price         当前价格
     * @param rsi           RSI值
     * @param changePercent 24h涨跌幅
     * @param llmCopy       LLM研判文案（可为空）
     * @return Markdown 格式消息字符串
     */
    private String buildDingTalkMessage(String symbol, String ruleType, double price,
                                        double rsi, double changePercent, String llmCopy) {
        String coin    = symbol.replace("USDT", "");
        String timeStr = LocalDateTime.now().format(TIME_FMT);

        String emoji, title, trigger;
        switch (ruleType) {
            case RULE_PRICE_UP -> {
                emoji   = "🚀";
                title   = coin + " 价格急涨提醒";
                trigger = String.format("5m涨幅 **+%.2f%%**（阈值 ±1%%）", changePercent);
            }
            case RULE_PRICE_DOWN -> {
                emoji   = "⚠️";
                title   = coin + " 价格急跌提醒";
                trigger = String.format("5m跌幅 **%.2f%%**（阈值 ±1%%）", changePercent);
            }
            case RULE_RSI_OB -> {
                emoji   = "🔴";
                title   = coin + " RSI 超买提醒";
                trigger = String.format("RSI **%.1f**（阈值 > 70）", rsi);
            }
            default -> {
                emoji   = "🟢";
                title   = coin + " RSI 超卖提醒";
                trigger = String.format("RSI **%.1f**（阈值 < 30）", rsi);
            }
        }

        String changeStr = changePercent >= 0
                ? String.format("+%.2f%%", changePercent)
                : String.format("%.2f%%", changePercent);

        StringBuilder sb = new StringBuilder();
        sb.append(emoji).append(" **").append(title).append("**\n\n");
        sb.append("触发：").append(trigger).append("\n\n");
        sb.append(String.format("价格：**%.2f USDT**  |  24h涨跌：%s\n\n", price, changeStr));
        sb.append("时间：").append(timeStr).append("\n\n");
        if (!llmCopy.isBlank()) {
            sb.append("---\n\n");
            sb.append("📊 **AI研判：** ").append(llmCopy);
        }
        return sb.toString();
    }

    /**
     * 通过 OkHttp 向钉钉 Webhook 推送 Markdown 消息，支持加签模式。
     * Webhook URL 未配置时跳过推送，仅打印日志。
     * 若配置了 dingtalk.secret，自动在 URL 上附加 timestamp 和 sign 参数。
     *
     * @param markdownContent Markdown 格式消息内容
     */
    private void pushDingTalk(String markdownContent) {
        if (dingTalkWebhookUrl == null || dingTalkWebhookUrl.isBlank()) {
            log.warn("DINGTALK_WEBHOOK_URL 未配置，跳过推送，消息内容：\n{}", markdownContent);
            return;
        }
        try {
            // 加签模式：拼接 timestamp + sign 到 URL
            String finalUrl = dingTalkWebhookUrl;
            if (dingTalkSecret != null && !dingTalkSecret.isBlank()) {
                long timestamp  = System.currentTimeMillis();
                String toSign   = timestamp + "\n" + dingTalkSecret;
                Mac mac         = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(dingTalkSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                String sign     = URLEncoder.encode(
                        Base64.getEncoder().encodeToString(mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8))),
                        StandardCharsets.UTF_8);
                finalUrl = dingTalkWebhookUrl + "&timestamp=" + timestamp + "&sign=" + sign;
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("msgtype", "markdown");
            body.put("markdown", Map.of(
                    "title", "CryptoMind 预警",
                    "text",  markdownContent
            ));
            String json = objectMapper.writeValueAsString(body);
            Request request = new Request.Builder()
                    .url(finalUrl)
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .build();
            try (Response response = okHttpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("钉钉推送失败，HTTP状态码：{}", response.code());
                } else {
                    log.info("钉钉推送成功");
                }
            }
        } catch (Exception e) {
            log.error("钉钉推送异常，原因：{}", e.getMessage(), e);
        }
    }
}
