package com.cryptomind.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI分析记录实体，保存每次加密货币分析的完整结果。
 * 模块：Chat / Analysis
 */
@Data
@Entity
@Table(name = "analysis_record", indexes = {
        @Index(name = "idx_analysis_record_session_id", columnList = "sessionId")
})
public class AnalysisRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属会话ID */
    @Column(nullable = false)
    private String sessionId;

    /** 分析的交易对，如 BTCUSDT */
    private String symbol;

    /** 投资建议：买入 / 卖出 / 观望 */
    private String suggestion;

    /** 置信度：0-100 */
    private Integer confidence;

    /** 技术指标分析摘要 */
    @Column(columnDefinition = "TEXT")
    private String technicalSummary;

    /** 新闻情绪分析摘要 */
    @Column(columnDefinition = "TEXT")
    private String newsSummary;

    /** 仓位风险建议 */
    @Column(columnDefinition = "TEXT")
    private String riskAdvice;

    /** Agent完整返回内容，用于审计和回溯 */
    @Column(columnDefinition = "TEXT")
    private String fullResponse;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}