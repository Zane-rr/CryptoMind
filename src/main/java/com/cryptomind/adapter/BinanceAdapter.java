package com.cryptomind.adapter;

import com.cryptomind.dto.PositionData;
import com.cryptomind.dto.PriceResponse;
import com.cryptomind.exception.ExchangeApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 币安交易所适配器，实现 ExchangeAdapter 接口，对接币安REST API。
 * 可通过新增实现类（如OkxAdapter）无缝扩展其他数据源。
 * 模块：Market
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceAdapter implements ExchangeAdapter {

    private static final String BASE_URL = "https://api.binance.com";

    /** K线强制拉取200根，满足MACD(12/26/9)等指标的预热需求 */
    private static final int KLINE_LIMIT = 200;

    /** HMAC算法名称 */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * {@inheritDoc}
     * 调用币安 GET /api/v3/ticker/24hr 接口获取24小时行情数据。
     *
     * @param symbol 交易对，如 BTCUSDT
     * @return 行情价格数据
     * @throws ExchangeApiException HTTP请求失败或响应解析异常时抛出
     */
    @Override
    public PriceResponse getPrice(String symbol) throws ExchangeApiException {
        String url = BASE_URL + "/api/v3/ticker/24hr?symbol=" + symbol;
        String body = executeGet(url);

        try {
            JsonNode node = objectMapper.readTree(body);
            PriceResponse response = new PriceResponse();
            response.setSymbol(node.get("symbol").asText());
            response.setLastPrice(new BigDecimal(node.get("lastPrice").asText()));
            response.setPriceChange(new BigDecimal(node.get("priceChange").asText()));
            response.setPriceChangePercent(new BigDecimal(node.get("priceChangePercent").asText()));
            response.setHighPrice(new BigDecimal(node.get("highPrice").asText()));
            response.setLowPrice(new BigDecimal(node.get("lowPrice").asText()));
            response.setVolume(new BigDecimal(node.get("volume").asText()));
            response.setQuoteVolume(new BigDecimal(node.get("quoteVolume").asText()));
            response.setUpdatedAt(LocalDateTime.now());
            return response;
        } catch (Exception e) {
            throw new ExchangeApiException("解析币安价格响应失败，symbol=" + symbol, e);
        }
    }

    /**
     * {@inheritDoc}
     * 调用币安 GET /api/v3/klines 接口，强制 limit=200 以满足EMA预热需求。
     * 返回格式：每根K线为 [openTimeMs, open, high, low, close, volume]。
     *
     * @param symbol   交易对，如 BTCUSDT
     * @param interval K线周期，如 1h / 4h / 1d
     * @return 200根K线数据列表
     * @throws ExchangeApiException HTTP请求失败或响应解析异常时抛出
     */
    @Override
    public List<BigDecimal[]> getKlines(String symbol, String interval) throws ExchangeApiException {
        String url = BASE_URL + "/api/v3/klines?symbol=" + symbol
                + "&interval=" + interval
                + "&limit=" + KLINE_LIMIT;
        String body = executeGet(url);

        try {
            JsonNode root = objectMapper.readTree(body);
            List<BigDecimal[]> klines = new ArrayList<>();
            for (JsonNode kline : root) {
                BigDecimal[] bar = new BigDecimal[6];
                bar[0] = BigDecimal.valueOf(kline.get(0).asLong()); // openTimeMs
                bar[1] = new BigDecimal(kline.get(1).asText());     // open
                bar[2] = new BigDecimal(kline.get(2).asText());     // high
                bar[3] = new BigDecimal(kline.get(3).asText());     // low
                bar[4] = new BigDecimal(kline.get(4).asText());     // close
                bar[5] = new BigDecimal(kline.get(5).asText());     // volume
                klines.add(bar);
            }
            return klines;
        } catch (Exception e) {
            throw new ExchangeApiException("解析币安K线响应失败，symbol=" + symbol + "，interval=" + interval, e);
        }
    }

    /**
     * {@inheritDoc}
     * 调用需要 HMAC-SHA256 签名的 GET /api/v3/account 接口获取持仓数据。
     * 过滤零余额资产，currentPrice 和 totalValue 由 Service 层补充。
     *
     * @param apiKey    明文API Key（调用方负责AES解密后传入）
     * @param secretKey 明文Secret Key（调用方负责AES解密后传入）
     * @return 非零余额持仓列表
     * @throws ExchangeApiException HTTP请求失败或签名异常时抛出
     */
    @Override
    public List<PositionData> getPortfolio(String apiKey, String secretKey) throws ExchangeApiException {
        long timestamp = System.currentTimeMillis();
        String queryString = "timestamp=" + timestamp;
        String signature = hmacSha256(secretKey, queryString);
        String url = BASE_URL + "/api/v3/account?" + queryString + "&signature=" + signature;

        Request request = new Request.Builder()
                .url(url)
                .addHeader("X-MBX-APIKEY", apiKey)
                .build();

        String body = executeRequest(request);

        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode balances = root.get("balances");
            /** 最小余额阈值，低于此值视为灰尘余额，不予展示 */
            BigDecimal DUST_THRESHOLD = new BigDecimal("0.00001");

            List<PositionData> result = new ArrayList<>();
            for (JsonNode balance : balances) {
                String asset = balance.get("asset").asText();
                BigDecimal free = new BigDecimal(balance.get("free").asText());
                BigDecimal locked = new BigDecimal(balance.get("locked").asText());

                // 过滤杠杆代币（以LD开头，如LDBTC、LDETH等，属于理财持仓非现货）
                if (asset.startsWith("LD")) {
                    continue;
                }
                // 过滤灰尘余额：free和locked都低于最小阈值时跳过
                if (free.compareTo(DUST_THRESHOLD) < 0 && locked.compareTo(DUST_THRESHOLD) < 0) {
                    continue;
                }

                PositionData position = new PositionData();
                position.setAsset(asset);
                position.setFree(free);
                position.setLocked(locked);
                result.add(position);
            }
            return result;
        } catch (Exception e) {
            throw new ExchangeApiException("解析币安持仓响应失败", e);
        }
    }

    // -------------------------------------------------------------------------
    // 私有辅助方法
    // -------------------------------------------------------------------------

    /**
     * 执行无需签名的 GET 请求并返回响应体字符串。
     *
     * @param url 请求URL
     * @return 响应体字符串
     * @throws ExchangeApiException HTTP调用失败时抛出
     */
    private String executeGet(String url) throws ExchangeApiException {
        Request request = new Request.Builder().url(url).build();
        return executeRequest(request);
    }

    /**
     * 执行 OkHttp 请求并返回响应体字符串。
     * 统一处理HTTP错误码和网络异常，确保外部失败以ExchangeApiException抛出。
     *
     * @param request OkHttp请求对象
     * @return 响应体字符串
     * @throws ExchangeApiException 非200状态码或网络异常时抛出
     */
    private String executeRequest(Request request) throws ExchangeApiException {
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new ExchangeApiException(
                        "币安API返回非200状态码：" + response.code() + "，url=" + request.url());
            }
            if (response.body() == null) {
                throw new ExchangeApiException("币安API响应体为空，url=" + request.url());
            }
            return response.body().string();
        } catch (ExchangeApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ExchangeApiException("币安API网络请求失败，url=" + request.url(), e);
        }
    }

    /**
     * 使用 HMAC-SHA256 算法对字符串签名，用于币安私有接口鉴权。
     *
     * @param secretKey 明文Secret Key
     * @param data      待签名的查询字符串
     * @return 十六进制签名字符串
     * @throws ExchangeApiException 签名过程中发生异常时抛出
     */
    private String hmacSha256(String secretKey, String data) throws ExchangeApiException {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secretKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new ExchangeApiException("HMAC-SHA256签名失败", e);
        }
    }

    /**
     * 将字节数组转换为小写十六进制字符串。
     *
     * @param bytes 字节数组
     * @return 十六进制字符串
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
