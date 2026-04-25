# Market Module

## Module Responsibility
Call Binance REST API to fetch real-time market data and compute technical indicators via Ta4j.
All interfaces are designed to provide structured data for the Agent layer.
Market display (K-line charts, hot symbol lists) is delegated to the frontend TradingView Widget.
The ExchangeAdapter abstraction layer supports future extension to OKX and other data sources.

## Architecture Overview

```
HTTP Request
    |
MarketController
    |
IMarketService (MarketServiceImpl)
    |-- Redis cache check (hit -> return immediately)
    |-- cache miss -> ExchangeAdapter (BinanceAdapter)
                          |-- Binance REST API
    |-- write result back to Redis
    |
Return to caller (Agent Tool / REST client)
```

## Redis Cache Design
| Key | Value | TTL | Description |
|-----|-------|-----|-------------|
| market:price:{symbol} | PriceResponse JSON | 30s | Real-time price cache |
| market:indicators:{symbol}:{interval} | IndicatorResponse JSON | 5min | Technical indicator cache |

## REST API
| Method | Path | Description |
|--------|------|-------------|
| GET | /api/market/price/{symbol} | Real-time price (24hr ticker) |
| GET | /api/market/indicators/{symbol}?interval=1h | Technical indicators (RSI/MACD/BB/Volume) |
| GET | /api/market/portfolio | Account spot holdings with real-time valuation |

## Service Workflow

### getPrice(symbol)
```
Redis hit (TTL 30s)  ->  return PriceResponse
Redis miss           ->  BinanceAdapter.getPrice() -> GET /api/v3/ticker/24hr
                     ->  write to Redis
                     ->  return PriceResponse
```

### getIndicators(symbol, interval)
```
Redis hit (TTL 5min)  ->  return IndicatorResponse
Redis miss            ->  fetchAndCalculate(symbol, interval)
                      ->  BinanceAdapter.getKlines() -> GET /api/v3/klines?limit=200
                      ->  buildBarSeries() -> Ta4j BarSeries (200 bars)
                      ->  calculateIndicators() -> take values at bar index 199 (latest)
                      ->  write to Redis
                      ->  return IndicatorResponse
```

### getPortfolio()
```
Blank key check       ->  throw ExchangeApiException if API key not configured
BinanceAdapter.getPortfolio(apiKey, secretKey)
    ->  HMAC-SHA256 sign queryString (timestamp)
    ->  GET /api/v3/account  (header: X-MBX-APIKEY)
    ->  parse balances[]
    ->  filter: skip assets starting with "LD" (leveraged tokens)
    ->  filter: skip assets where free < 0.00001 AND locked < 0.00001 (dust)
For each remaining position:
    ->  asset == "USDT"  ->  currentPrice = 1
    ->  otherwise        ->  getPrice(asset + "USDT").lastPrice
    ->  totalValue = free * currentPrice
return List<PositionData>
```

## DTO Design

### PriceResponse
| Field | Type | Source |
|-------|------|--------|
| symbol | String | Binance ticker |
| lastPrice | BigDecimal | lastPrice |
| priceChange | BigDecimal | priceChange |
| priceChangePercent | BigDecimal | priceChangePercent |
| highPrice | BigDecimal | highPrice |
| lowPrice | BigDecimal | lowPrice |
| volume | BigDecimal | volume |
| quoteVolume | BigDecimal | quoteVolume |
| updatedAt | LocalDateTime | server time |

### IndicatorResponse
| Field | Type | Description |
|-------|------|-------------|
| symbol | String | Trading pair |
| interval | String | 1h / 4h / 1d |
| rsi | BigDecimal | RSI(14) |
| macdValue | BigDecimal | MACD line (EMA12 - EMA26) |
| macdSignal | BigDecimal | Signal line EMA(9) |
| macdHistogram | BigDecimal | macdValue - macdSignal |
| bollingerUpper | BigDecimal | BB upper band |
| bollingerMiddle | BigDecimal | BB middle (SMA20) |
| bollingerLower | BigDecimal | BB lower band |
| volumeTrend | String | High / Low / Normal volume |
| updatedAt | LocalDateTime | Calculation time |

### PositionData
| Field | Type | Description |
|-------|------|-------------|
| asset | String | Asset name, e.g. BTC |
| free | BigDecimal | Available balance |
| locked | BigDecimal | Locked (in open orders) |
| currentPrice | BigDecimal | Filled by Service via getPrice() |
| totalValue | BigDecimal | free * currentPrice |
| accountType | String | Fixed: "SPOT" |
| note | String | Reminder that savings/staking balances require separate query |

## Technical Indicator Calculation
| Indicator | Params | Warmup | Signal |
|-----------|--------|--------|--------|
| RSI | 14 bars | 200 bars required | <30 oversold, >70 overbought |
| MACD | 12/26/9 | 200 bars required | Golden cross bullish, death cross bearish |
| Bollinger Bands | 20 bars | 200 bars required | Upper band resistance, lower band support |
| Volume | SMA(20) comparison | 200 bars required | High volume confirms trend |

## Adapter Layer

### ExchangeAdapter Interface
```java
public interface ExchangeAdapter {
    PriceResponse getPrice(String symbol) throws ExchangeApiException;
    List<BigDecimal[]> getKlines(String symbol, String interval) throws ExchangeApiException;
    List<PositionData> getPortfolio(String apiKey, String secretKey) throws ExchangeApiException;
}
```

### BinanceAdapter
- Base URL: `https://api.binance.com`
- `getKlines`: forced `limit=200` for indicator warmup; returns `[openTimeMs, open, high, low, close, volume]`
- `getPortfolio`: HMAC-SHA256 signed request; filters LD-prefix tokens and dust balances

## API Key Configuration
Binance API keys are read from `application-local.properties` (local profile) via Spring `@Value`.
Keys are **never stored in the database** at the current local deployment stage.

```properties
binance.api-key=<your api key>
binance.secret-key=<your secret key>
```

`application-local.properties` is listed in `.gitignore` and must not be committed.

## Constraints
- MACD requires 200-bar warmup; fewer bars will throw ExchangeApiException
- All external API failures must throw ExchangeApiException — returning null is prohibited
- Sensitive keys must never appear in logs
