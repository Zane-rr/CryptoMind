package com.cryptomind.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单条消息DTO，用于历史记录列表返回。
 * 模块：Chat
 */
@Data
public class ChatMessageDTO {

    /** 消息数据库主键 */
    private Long id;

    /** 消息角色：user 或 assistant */
    private String role;

    /** 消息内容 */
    private String content;

    /** 消息创建时间 */
    private LocalDateTime createdAt;
}
