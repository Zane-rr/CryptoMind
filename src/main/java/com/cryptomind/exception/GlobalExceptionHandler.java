package com.cryptomind.exception;

import com.cryptomind.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;

/**
 * 全局异常处理器，统一捕获业务异常和未知异常，返回结构化错误响应。
 * 模块：通用
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理交易所API调用失败异常（如交易对不存在、网络超时等）。
     *
     * @param e ExchangeApiException
     * @return 400 + ErrorResponse
     */
    @ExceptionHandler(ExchangeApiException.class)
    public ResponseEntity<ErrorResponse> handleExchangeApiException(ExchangeApiException e) {
        log.warn("交易所API异常：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(400, e.getMessage(), LocalDateTime.now()));
    }

    /**
     * 处理新闻/情绪数据源调用失败异常。
     *
     * @param e NewsApiException
     * @return 400 + ErrorResponse
     */
    @ExceptionHandler(NewsApiException.class)
    public ResponseEntity<ErrorResponse> handleNewsApiException(NewsApiException e) {
        log.warn("新闻API异常：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(400, e.getMessage(), LocalDateTime.now()));
    }

    /**
     * 兜底处理所有未预期异常，避免堆栈信息暴露给调用方。
     *
     * @param e Exception
     * @return 500 + ErrorResponse
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("未预期异常：{}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(500, "服务器内部错误，请稍后重试", LocalDateTime.now()));
    }
}