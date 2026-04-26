package com.cryptomind.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 自选监控规则实体，每条记录代表用户手动添加的一个待监控币种。
 * 所有规则（价格涨跌幅、RSI超买、RSI超卖）对该 symbol 全量运行。
 * 模块：Alert
 */
@Data
@Entity
@Table(name = "alert_rules",
       uniqueConstraints = @UniqueConstraint(columnNames = "symbol"))
public class AlertRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 监控的交易对，如 BTCUSDT，唯一约束防止重复添加 */
    @Column(nullable = false, length = 20)
    private String symbol;

    /** 创建时间 */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
