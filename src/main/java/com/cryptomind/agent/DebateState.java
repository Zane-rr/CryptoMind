package com.cryptomind.agent;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.state.AgentState;


import java.util.Map;

/**
 * LangGraph4J辩论图的共享状态，贯穿 expert_debate → cio_cross → cio_final 全流程。
 * 继承 AgentState（本质是一个 Map），各节点通过返回 Map<String, Object> 更新字段。
 * 模块：Agent
 */
public class DebateState extends AgentState {

    @SuppressWarnings("rawtypes")
    public DebateState(Map initData) {
        super(initData);
    }

    /**
     * 投研情报简报，由 gatherIntelligence() 生成，所有专家节点共享读取。
     *
     * @return 投研情报简报字符串
     */
    public String getBrief() {
        return (String) data().getOrDefault("brief", "");
    }

    /**
     * 用户原始问题，由初始State携带。
     *
     * @return 用户问题字符串
     */
    public String getQuestion() {
        return (String) data().getOrDefault("question", "");
    }

    /**
     * 多头分析师（BULLISH）的报告JSON字符串。
     * 每轮 expert_debate 后更新，第二轮包含对CIO质询的回应。
     *
     * @return BULLISH报告JSON，默认空字符串
     */
    public String getBullishReport() {
        return (String) data().getOrDefault("bullishReport", "");
    }

    /**
     * 风控师（RISK）的报告JSON字符串。
     *
     * @return RISK报告JSON，默认空字符串
     */
    public String getRiskReport() {
        return (String) data().getOrDefault("riskReport", "");
    }

    /**
     * 量化策略师（QUANT）的报告JSON字符串。
     *
     * @return QUANT报告JSON，默认空字符串
     */
    public String getQuantReport() {
        return (String) data().getOrDefault("quantReport", "");
    }

    /**
     * CIO质询内容JSON字符串，由 cio_cross 节点生成，下一轮 expert_debate 消费。
     * 第一轮前为空，每轮 expert_debate 执行后清空。
     *
     * @return 质询JSON，默认空字符串
     */
    public String getCrossExamination() {
        return (String) data().getOrDefault("crossExamination", "");
    }

    /**
     * 多头分析师对CIO质询的补充回应（预留字段，供后续扩展单独回应逻辑使用）。
     *
     * @return BULLISH回应字符串，默认空字符串
     */
    public String getBullishRebuttal() {
        return (String) data().getOrDefault("bullishRebuttal", "");
    }

    /**
     * 风控师对CIO质询的补充回应（预留字段）。
     *
     * @return RISK回应字符串，默认空字符串
     */
    public String getRiskRebuttal() {
        return (String) data().getOrDefault("riskRebuttal", "");
    }

    /**
     * 量化策略师对CIO质询的补充回应（预留字段）。
     *
     * @return QUANT回应字符串，默认空字符串
     */
    public String getQuantRebuttal() {
        return (String) data().getOrDefault("quantRebuttal", "");
    }

    /**
     * CIO最终裁决结果JSON字符串，由 cio_final 节点生成。
     *
     * @return CIO裁决JSON，默认空字符串
     */
    public String getCioResult() {
        return (String) data().getOrDefault("cioResult", "");
    }

    /**
     * CIO综合评分（0~100），由 cio_final 解析后写入。
     * 条件边根据此分值决定是否继续辩论或结束。
     *
     * @return 综合评分，默认0
     */
    public int getScore() {
        return (int) data().getOrDefault("score", 0);
    }

    /**
     * 当前辩论轮次（从1开始），每次 cio_final 完成后自增。
     * 与 MAX_DEBATE_ROUNDS 比较，超出则强制结束辩论。
     *
     * @return 当前轮次，默认1
     */
    public int getRound() {
        return (int) data().getOrDefault("round", 1);
    }
}
