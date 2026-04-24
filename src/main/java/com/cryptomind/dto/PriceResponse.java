package com.cryptomind.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 行情价格响应DTO，字段与币安 ticker/24hr 接口一一对应。
 * 模块：Market
 */
@Data
public class PriceResponse {

    /** 交易对，如 BTCUSDT */
    private String symbol;

    /** 最新成交价 */
    private BigDecimal lastPrice;

    /** 24小时价格变动额 */
    private BigDecimal priceChange;

    /** 24小时价格变动百分比 */
    private BigDecimal priceChangePercent;

    /** 24小时最高价 */
    private BigDecimal highPrice;

    /** 24小时最低价 */
    private BigDecimal lowPrice;

    /** 24小时成交量（基础币种，如BTC） */
    private BigDecimal volume;

    /** 24小时成交额（计价币种，如USDT） */
    private BigDecimal quoteVolume;

    /** 数据更新时间 */
    private LocalDateTime updatedAt;
}
