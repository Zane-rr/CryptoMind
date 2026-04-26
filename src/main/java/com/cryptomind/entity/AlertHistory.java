package com.cryptomind.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 预警推送历史实体，每次触发推送记录一条。
 * 用途：审计日志 + Redis 重启后的冷却兜底检查。
 * 模块：Alert
 */
@Data
@Entity
@Table(name = "alert_history",
       indexes = {
           @Index(name = "idx_alert_history_symbol_rule", columnList = "symbol, ruleType"),
           @Index(name = "idx_alert_history_triggered_at", columnList = "triggeredAt")
       })
public class AlertHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 触发预警的交易对，如 BTCUSDT */
    @Column(nullable = false, length = 20)
    private String symbol;

    /** 规则类型：PRICE_UP / PRICE_DOWN / RSI_OVERBOUGHT / RSI_OVERSOLD */
    @Column(nullable = false, length = 20)
    private String ruleType;

    /** 触发时的价格（USDT） */
    private Double price;

    /** 触发时的 RSI 值，价格类规则可为 null */
    private Double rsiValue;

    /** 触发时的 24h 涨跌幅（百分比，如 +8.6 或 -6.2） */
    private Double priceChangePercent;

    /** LLM 生成的一句话研判文案 */
    @Column(length = 500)
    private String llmCopy;

    /** 推送时间 */
    @Column(nullable = false)
    private LocalDateTime triggeredAt;

    @PrePersist
    void prePersist() {
        this.triggeredAt = LocalDateTime.now();
    }
}
