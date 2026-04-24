package com.cryptomind.exception;

/**
 * 交易所外部API调用失败异常。
 * 所有外部API调用（币安、OKX等）失败时抛出此异常，禁止返回null。
 * 模块：Market / Exchange
 */
public class ExchangeApiException extends RuntimeException {

    /**
     * 构造异常（无根因）。
     *
     * @param message 错误描述
     */
    public ExchangeApiException(String message) {
        super(message);
    }

    /**
     * 构造异常（含根因）。
     *
     * @param message 错误描述
     * @param cause   根因异常
     */
    public ExchangeApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
