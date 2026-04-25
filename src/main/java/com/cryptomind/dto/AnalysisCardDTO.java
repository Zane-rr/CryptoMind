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

    /** 建议开仓价位，如"当前价附近95000-96000区间" */
    private String entryPrice;

    /** 止盈目标，如"第一目标:98000 USDT，第二目标:102000 USDT" */
    private String takeProfitTargets;

    /** 止损价位，如"止损价位:93000 USDT，跌破此位离场" */
    private String stopLoss;

    /** 建议仓位比例，如"建议仓位占总资金的10%-15%，轻仓试探" */
    private String positionSize;
}
