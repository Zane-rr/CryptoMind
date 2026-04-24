package com.cryptomind.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话实体，记录每个对话会话的元信息。
 * 模块：Chat
 */
@Data
@Entity
@Table(name = "chat_session", indexes = {
        @Index(name = "idx_chat_session_session_id", columnList = "sessionId", unique = true)
})
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话唯一标识，UUID格式 */
    @Column(nullable = false, unique = true)
    private String sessionId;

    /** 会话标题，取第一条用户消息前20个字符 */
    private String title;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
