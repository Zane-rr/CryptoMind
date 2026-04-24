package com.cryptomind.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 全局统一错误响应DTO。
 * 模块：通用
 */
@Data
@AllArgsConstructor
public class ErrorResponse {

    /** HTTP状态码 */
    private Integer code;

    /** 错误描述信息 */
    private String message;

    /** 错误发生时间 */
    private LocalDateTime timestamp;
}
