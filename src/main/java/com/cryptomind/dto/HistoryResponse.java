package com.cryptomind.dto;

import lombok.Data;

import java.util.List;

/**
 * 会话历史响应DTO。
 * 模块：Chat
 */
@Data
public class HistoryResponse {

    /** 会话ID */
    private String sessionId;

    /** 该会话下的消息列表，按时间正序排列 */
    private List<ChatMessageDTO> messages;
}
