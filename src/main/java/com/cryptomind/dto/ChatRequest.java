package com.cryptomind.dto;

import lombok.Data;

/**
 * 用户提问请求DTO。
 * 模块：Chat
 */
@Data
public class ChatRequest {

    /** 会话ID，为空时服务端自动创建新会话 */
    private String sessionId;

    /** 用户问题 */
    private String question;

    /** 交易对（可选），为空时由Agent自动从问题中识别 */
    private String symbol;
}
