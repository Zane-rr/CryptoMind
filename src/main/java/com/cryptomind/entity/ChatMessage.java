package com.cryptomind.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话消息实体，记录会话中每一条用户或助手的消息。
 * 模块：Chat
 */
@Data
@Entity
@Table(name = "chat_message", indexes = {
        @Index(name = "idx_chat_message_session_id", columnList = "sessionId")
})
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属会话ID */
    @Column(nullable = false)
    private String sessionId;

    /** 消息角色：user 或 assistant */
    @Column(nullable = false)
    private String role;

    /** 消息正文，允许长文本 */
    @Column(columnDefinition = "TEXT")
    private String content;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}