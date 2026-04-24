package com.cryptomind.service;

import com.cryptomind.dto.IndicatorResponse;
import com.cryptomind.dto.PositionData;
import com.cryptomind.dto.PriceResponse;
import com.cryptomind.exception.ExchangeApiException;

import java.util.List;

/**
 * Market模块服务接口，定义行情查询、指标计算、持仓查询的标准契约。
 * 模块：Market
 */
public interface IMarketService {

    /**
     * 查询指定交易对的实时价格。优先读Redis缓存（30秒TTL），
     * 缓存未命中则调用Adapter获取并回填缓存。
     *
     * @param symbol 交易对，如 BTCUSDT
     * @return 行情价格数据
     * @throws ExchangeApiException 外部API调用失败时抛出
     */
    PriceResponse getPrice(String symbol) throws ExchangeApiException;

    /**
     * 查询指定交易对的技术指标。优先读Redis缓存（5分钟TTL），
     * 缓存未命中则拉取K线计算后回填缓存。
     *
     * @param symbol   交易对，如 BTCUSDT
     * @param interval K线周期，如 1h / 4h / 1d
     * @return 技术指标数据
     * @throws ExchangeApiException 外部API调用失败时抛出
     */
    IndicatorResponse getIndicators(String symbol, String interval) throws ExchangeApiException;

    /**
     * 拉取200根K线并计算技术指标，结果存入Redis缓存。
     * 由 getIndicators 在缓存未命中时内部调用。
     *
     * @param symbol   交易对，如 BTCUSDT
     * @param interval K线周期
     * @return 技术指标数据
     * @throws ExchangeApiException 外部API调用失败时抛出
     */
    IndicatorResponse fetchAndCalculate(String symbol, String interval) throws ExchangeApiException;

    /**
     * 查询账户持仓列表。从数据库读取AES加密的API Key并解密后调用Adapter。
     * 当前阶段暂返回空列表，Exchange模块完成后实现。
     *
     * @return 持仓列表
     */
    List<PositionData> getPortfolio();

}
