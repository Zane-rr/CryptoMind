package com.cryptomind.service.impl;

import com.cryptomind.adapter.ExchangeAdapter;
import com.cryptomind.dto.IndicatorResponse;
import com.cryptomind.dto.PositionData;
import com.cryptomind.dto.PriceResponse;
import com.cryptomind.exception.ExchangeApiException;
import com.cryptomind.service.IMarketService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * IMarketService 的默认实现，负责行情缓存、技术指标计算和热门币对刷新。
 * 模块：Market
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketServiceImpl implements IMarketService {

    // -------------------------------------------------------------------------
    // Redis Key 前缀
    // -------------------------------------------------------------------------
    private static final String REDIS_KEY_PRICE      = "market:price:";
    private static final String REDIS_KEY_INDICATORS = "market:indicators:";

    // -------------------------------------------------------------------------
    // Redis 缓存过期时间
    // -------------------------------------------------------------------------
    private static final Duration PRICE_TTL     = Duration.ofSeconds(30);
    private static final Duration INDICATOR_TTL = Duration.ofMinutes(5);

    // -------------------------------------------------------------------------
    // 技术指标计算参数
    // -------------------------------------------------------------------------
    /** 强制拉取K线根数，用于MACD(12/26/9)充分预热 */
    private static final int TOTAL_BARS  = 200;

    /** RSI计算周期 */
    private static final int RSI_PERIOD  = 14;

    /** MACD快线周期 */
    private static final int MACD_FAST   = 12;

    /** MACD慢线周期 */
    private static final int MACD_SLOW   = 26;

    /** MACD信号线周期 */
    private static final int MACD_SIGNAL = 9;

    /** 布林带/均量计算周期 */
    private static final int BB_PERIOD   = 20;

    /** 成交量放量判断阈值（高于均量120%视为放量） */
    private static final double VOLUME_HIGH_THRESHOLD = 1.2;

    /** 成交量缩量判断阈值（低于均量80%视为缩量） */
    private static final double VOLUME_LOW_THRESHOLD  = 0.8;

    /** 指标结果保留小数位数 */
    private static final int SCALE = 8;

    private final ExchangeAdapter exchangeAdapter;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${binance.api-key}")
    private String binanceApiKey;

    @Value("${binance.secret-key}")
    private String binanceSecretKey;

    /**
     * {@inheritDoc}
     */
    @Override
    public PriceResponse getPrice(String symbol) throws ExchangeApiException {
        symbol = symbol.toUpperCase().trim();
        if (!symbol.endsWith("USDT")) {
            symbol = symbol + "USDT";
        }
        String redisKey = REDIS_KEY_PRICE + symbol;
        String cached = redisTemplate.opsForValue().get(redisKey);

        if (cached != null) {
            try {
                return objectMapper.readValue(cached, PriceResponse.class);
            } catch (Exception e) {
                // 反序列化失败降级到API，不影响主流程
                log.warn("价格缓存反序列化失败，降级到API，symbol={}，原因：{}", symbol, e.getMessage());
            }
        }

        PriceResponse price = exchangeAdapter.getPrice(symbol);
        cacheToRedis(redisKey, price, PRICE_TTL);
        return price;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IndicatorResponse getIndicators(String symbol, String interval) throws ExchangeApiException {
        symbol = symbol.toUpperCase().trim();
        if (!symbol.endsWith("USDT")) {
            symbol = symbol + "USDT";
        }
        String redisKey = REDIS_KEY_INDICATORS + symbol + ":" + interval;
        String cached = redisTemplate.opsForValue().get(redisKey);

        if (cached != null) {
            try {
                return objectMapper.readValue(cached, IndicatorResponse.class);
            } catch (Exception e) {
                log.warn("指标缓存反序列化失败，降级到计算，symbol={}，原因：{}", symbol, e.getMessage());
            }
        }

        return fetchAndCalculate(symbol, interval);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IndicatorResponse fetchAndCalculate(String symbol, String interval) throws ExchangeApiException {
        List<BigDecimal[]> rawKlines = exchangeAdapter.getKlines(symbol, interval);

        if (rawKlines.size() < TOTAL_BARS) {
            throw new ExchangeApiException(
                    "K线数量不足，预期" + TOTAL_BARS + "根，实际" + rawKlines.size() + "根，symbol=" + symbol);
        }

        IndicatorResponse result = calculateIndicators(rawKlines, symbol, interval);

        String redisKey = REDIS_KEY_INDICATORS + symbol + ":" + interval;
        cacheToRedis(redisKey, result, INDICATOR_TTL);
        return result;
    }

    /**
     * {@inheritDoc}
     * 使用配置文件中的API Key调用币安账户接口，获取非零余额持仓列表。
     * 对每个持仓资产补充实时价格（asset+USDT交易对），USDT本身单价固定为1。
     * currentPrice获取失败时不阻断返回，仅记录warn日志并跳过价格补充。
     */
    @Override
    public List<PositionData> getPortfolio() {
        if (binanceApiKey == null || binanceApiKey.isBlank()
                || binanceSecretKey == null || binanceSecretKey.isBlank()) {
            throw new ExchangeApiException("Binance API Key 未配置，请在环境变量中设置 BINANCE_API_KEY 和 BINANCE_SECRET_KEY");
        }
        List<PositionData> positions = exchangeAdapter.getPortfolio(binanceApiKey, binanceSecretKey);

        for (PositionData position : positions) {
            String asset = position.getAsset();
            try {
                BigDecimal price;
                if ("USDT".equals(asset)) {
                    // USDT本身单价固定为1，无需调用行情接口
                    price = BigDecimal.ONE;
                } else {
                    price = getPrice(asset + "USDT").getLastPrice();
                }
                position.setCurrentPrice(price);
                position.setTotalValue(position.getFree().multiply(price)
                        .setScale(SCALE, java.math.RoundingMode.HALF_UP));
            } catch (Exception e) {
                // 价格补充失败不影响持仓列表返回，仅记录warn
                log.warn("获取{}实时价格失败，跳过价格补充，原因：{}", asset, e.getMessage());
            }
        }

        return positions;
    }

    // -------------------------------------------------------------------------
    // 私有方法：技术指标计算
    // -------------------------------------------------------------------------

    /**
     * 基于200根K线原始数据计算RSI、MACD、布林带和成交量趋势。
     * 前170根作为指标预热，只取第200根（最新一根）的指标值，
     * 确保计算结果与主流交易所一致。
     *
     * @param rawKlines 200根K线，每根格式为 [openTimeMs, open, high, low, close, volume]
     * @param symbol    交易对
     * @param interval  K线周期
     * @return 指标计算结果
     */
    private IndicatorResponse calculateIndicators(List<BigDecimal[]> rawKlines,
                                                  String symbol, String interval) {
        BarSeries series = buildBarSeries(rawKlines, symbol);
        int lastIndex = series.getEndIndex();

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // RSI(14)
        RSIIndicator rsi = new RSIIndicator(closePrice, RSI_PERIOD);

        // MACD(12, 26) + 信号线(9)
        MACDIndicator macd       = new MACDIndicator(closePrice, MACD_FAST, MACD_SLOW);
        EMAIndicator  macdSignal = new EMAIndicator(macd, MACD_SIGNAL);

        // 布林带(20)
        SMAIndicator              sma20    = new SMAIndicator(closePrice, BB_PERIOD);
        StandardDeviationIndicator sd20    = new StandardDeviationIndicator(closePrice, BB_PERIOD);
        BollingerBandsMiddleIndicator bbMid   = new BollingerBandsMiddleIndicator(sma20);
        BollingerBandsUpperIndicator  bbUpper = new BollingerBandsUpperIndicator(bbMid, sd20);
        BollingerBandsLowerIndicator  bbLower = new BollingerBandsLowerIndicator(bbMid, sd20);

        // 成交量趋势
        VolumeIndicator volumeIndicator = new VolumeIndicator(series);
        SMAIndicator    volumeSma       = new SMAIndicator(volumeIndicator, BB_PERIOD);

        // 取最新一根指标值（第200根）
        BigDecimal rsiVal       = toBigDecimal(rsi.getValue(lastIndex));
        BigDecimal macdVal      = toBigDecimal(macd.getValue(lastIndex));
        BigDecimal signalVal    = toBigDecimal(macdSignal.getValue(lastIndex));
        BigDecimal histogram    = macdVal.subtract(signalVal).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal bbUpperVal   = toBigDecimal(bbUpper.getValue(lastIndex));
        BigDecimal bbMiddleVal  = toBigDecimal(bbMid.getValue(lastIndex));
        BigDecimal bbLowerVal   = toBigDecimal(bbLower.getValue(lastIndex));
        String     volumeTrend  = resolveVolumeTrend(
                volumeIndicator.getValue(lastIndex).doubleValue(),
                volumeSma.getValue(lastIndex).doubleValue());

        IndicatorResponse response = new IndicatorResponse();
        response.setSymbol(symbol);
        response.setInterval(interval);
        response.setRsi(rsiVal);
        response.setMacdValue(macdVal);
        response.setMacdSignal(signalVal);
        response.setMacdHistogram(histogram);
        response.setBollingerUpper(bbUpperVal);
        response.setBollingerMiddle(bbMiddleVal);
        response.setBollingerLower(bbLowerVal);
        response.setVolumeTrend(volumeTrend);
        response.setUpdatedAt(LocalDateTime.now());
        return response;
    }

    /**
     * 将原始K线列表构建为 Ta4j BarSeries。
     * K线的 openTimeMs 作为Bar的结束时间（UTC），避免时区偏差影响指标。
     *
     * @param rawKlines 原始K线列表
     * @param name      BarSeries名称，用于日志标识
     * @return Ta4j BarSeries
     */
    private BarSeries buildBarSeries(List<BigDecimal[]> rawKlines, String name) {
        BarSeries series = new BaseBarSeries(name);
        for (BigDecimal[] kline : rawKlines) {
            ZonedDateTime endTime = Instant.ofEpochMilli(kline[0].longValue())
                    .atZone(ZoneOffset.UTC);
            series.addBar(endTime,
                    kline[1], // open
                    kline[2], // high
                    kline[3], // low
                    kline[4], // close
                    kline[5]  // volume
            );
        }
        return series;
    }

    /**
     * 根据最新一根K线成交量与20周期均量的比值判断成交量趋势。
     *
     * @param lastVolume 最新一根K线成交量
     * @param avgVolume  20周期平均成交量
     * @return 放量 / 缩量 / 平量
     */
    private String resolveVolumeTrend(double lastVolume, double avgVolume) {
        if (avgVolume == 0) {
            return "平量";
        }
        if (lastVolume > avgVolume * VOLUME_HIGH_THRESHOLD) {
            return "放量";
        } else if (lastVolume < avgVolume * VOLUME_LOW_THRESHOLD) {
            return "缩量";
        }
        return "平量";
    }

    /**
     * 将 Ta4j Num 转换为保留8位小数的 BigDecimal。
     *
     * @param num Ta4j Num类型指标值
     * @return BigDecimal
     */
    private BigDecimal toBigDecimal(org.ta4j.core.num.Num num) {
        return BigDecimal.valueOf(num.doubleValue()).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 将对象序列化为JSON后写入Redis，失败时仅记录日志，不影响主流程。
     *
     * @param key   Redis Key
     * @param value 待缓存对象
     * @param ttl   过期时间
     */
    private void cacheToRedis(String key, Object value, Duration ttl) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json, ttl);
        } catch (Exception e) {
            log.warn("Redis缓存写入失败，key={}，原因：{}", key, e.getMessage());
        }
    }
}
