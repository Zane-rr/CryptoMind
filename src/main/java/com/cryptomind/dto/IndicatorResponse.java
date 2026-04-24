package com.cryptomind.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 技术指标响应DTO，基于200根K线、前170根预热后计算的最新一根指标值。
 * 模块：Market
 */
@Data
public class IndicatorResponse {

    /** 交易对，如 BTCUSDT */
    private String symbol;

    /** K线周期，如 1h / 4h / 1d */
    private String interval;

    /** RSI（14周期）：< 30超卖，> 70超买 */
    private BigDecimal rsi;

    /** MACD线（12/26 EMA差） */
    private BigDecimal macdValue;

    /** MACD信号线（MACD的9周期EMA） */
    private BigDecimal macdSignal;

    /** MACD柱（macdValue - macdSignal）：正值看多，负值看空 */
    private BigDecimal macdHistogram;

    /** 布林带上轨（压力位） */
    private BigDecimal bollingerUpper;

    /** 布林带中轨（20周期SMA） */
    private BigDecimal bollingerMiddle;

    /** 布林带下轨（支撑位） */
    private BigDecimal bollingerLower;

    /** 成交量趋势：放量 / 缩量 / 平量（与20周期均量对比） */
    private String volumeTrend;

    /** 指标计算时间 */
    private LocalDateTime updatedAt;
}
