package com.cryptomind.dto;

import lombok.Data;

/**
 * 分析结果卡片DTO，仅在分析类问题的响应中携带。
 * 模块：Chat / Analysis
 */
@Data
public class AnalysisCardDTO {

    /** 交易对，如 BTCUSDT */
    private String symbol;

    /** 投资建议：买入 / 卖出 / 观望 */
    private String suggestion;

    /** 置信度：0-100 */
    private Integer confidence;

    /** 技术指标分析摘要 */
    private String technicalSummary;

    /** 新闻情绪分析摘要 */
    private String newsSummary;

    /** 仓位风险建议 */
    private String riskAdvice;
}
