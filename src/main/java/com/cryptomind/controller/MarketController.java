package com.cryptomind.controller;

import com.cryptomind.dto.IndicatorResponse;
import com.cryptomind.dto.PositionData;
import com.cryptomind.dto.PriceResponse;
import com.cryptomind.exception.ExchangeApiException;
import com.cryptomind.service.IMarketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Market模块REST控制器，提供实时行情和技术指标查询接口。
 * 模块：Market
 */
@Slf4j
@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
@Tag(name = "Market", description = "行情与技术指标")
public class MarketController {

    private final IMarketService marketService;

    /**
     * 查询指定交易对的实时价格（Redis缓存30秒）。
     *
     * @param symbol 交易对，如 BTCUSDT
     * @return 行情价格数据
     * @throws ExchangeApiException 交易所API调用失败时抛出
     */
    @Operation(summary = "查询当前价格")
    @GetMapping("/price/{symbol}")
    public ResponseEntity<PriceResponse> getPrice(@PathVariable String symbol) {
        PriceResponse price = marketService.getPrice(symbol);
        return ResponseEntity.ok(price);
    }

    /**
     * 查询指定交易对的技术指标（Redis缓存5分钟）。
     *
     * @param symbol   交易对，如 BTCUSDT
     * @param interval K线周期，默认1h，支持 1h / 4h / 1d
     * @return 技术指标数据（RSI、MACD、布林带、成交量趋势）
     * @throws ExchangeApiException 交易所API调用失败时抛出
     */
    @Operation(summary = "查询技术指标")
    @GetMapping("/indicators/{symbol}")
    public ResponseEntity<IndicatorResponse> getIndicators(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1h") String interval) {
        IndicatorResponse indicators = marketService.getIndicators(symbol, interval);
        return ResponseEntity.ok(indicators);
    }

    /**
     * 查询当前账户持仓列表，含实时单价和持仓总价值。
     *
     * @return 非零余额持仓列表
     */
    @Operation(summary = "查询账户持仓")
    @GetMapping("/portfolio")
    public ResponseEntity<List<PositionData>> getPortfolio() {
        return ResponseEntity.ok(marketService.getPortfolio());
    }

}
