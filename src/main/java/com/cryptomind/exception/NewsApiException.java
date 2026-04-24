package com.cryptomind.exception;

/**
 * 新闻/情绪数据源调用失败时抛出的运行时异常。
 * 模块：News / Agent
 */
public class NewsApiException extends RuntimeException {

    public NewsApiException(String message) {
        super(message);
    }

    public NewsApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
