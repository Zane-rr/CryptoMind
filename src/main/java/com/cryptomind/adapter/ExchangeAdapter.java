package com.cryptomind.adapter;

import com.cryptomind.dto.PositionData;
import com.cryptomind.dto.PriceResponse;
import com.cryptomind.exception.ExchangeApiException;

import java.math.BigDecimal;
import java.util.List;

/**
 * 交易所数据适配器接口，统一抽象各交易所的数据获取行为。
 * 通过此接口可无缝扩展 OKX、Bybit 等数据源，无需修改上层Service。
 * 模块：Market
 */
public interface ExchangeAdapter {

    /**
     * 获取指定交易对的实时行情数据。
     *
     * @param symbol 交易对，如 BTCUSDT
     * @return 行情价格数据
     * @throws ExchangeApiException 接口调用失败时抛出
     */
    PriceResponse getPrice(String symbol) throws ExchangeApiException;

    /**
     * 获取指定交易对的K线数据（强制limit=200，满足MACD预热需求）。
     * 返回的 BigDecimal[] 格式：[openTimeMs, open, high, low, close, volume]
     *
     * @param symbol   交易对，如 BTCUSDT
     * @param interval K线周期，如 1h / 4h / 1d
     * @return K线列表，每根为长度6的BigDecimal数组
     * @throws ExchangeApiException 接口调用失败时抛出
     */
    List<BigDecimal[]> getKlines(String symbol, String interval) throws ExchangeApiException;

    /**
     * 获取账户持仓列表（调用需要签名的私有接口）。
     * 返回原始持仓数据，currentPrice 和 totalValue 由 Service 层补充。
     *
     * @param apiKey    明文API Key（Service层AES解密后传入）
     * @param secretKey 明文Secret Key（Service层AES解密后传入）
     * @return 非零余额的持仓列表
     * @throws ExchangeApiException 接口调用失败时抛出
     */
    List<PositionData> getPortfolio(String apiKey, String secretKey) throws ExchangeApiException;

}
