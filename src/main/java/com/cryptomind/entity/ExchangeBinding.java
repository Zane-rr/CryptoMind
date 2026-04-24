package com.cryptomind.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 交易所绑定实体，存储经过AES-256加密的API Key和Secret Key。
 * 解密操作仅在内存中进行，禁止明文落库。
 * 模块：Market / Exchange
 */
@Data
@Entity
@Table(name = "exchange_binding")
public class ExchangeBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 交易所名称，如 binance */
    @Column(nullable = false, length = 20)
    private String platform;

    /** AES-256加密后的API Key */
    @Column(nullable = false)
    private String apiKey;

    /** AES-256加密后的Secret Key */
    @Column(nullable = false)
    private String secretKey;

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
