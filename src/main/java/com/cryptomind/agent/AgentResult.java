package com.cryptomind.agent;

import com.cryptomind.dto.AnalysisCardDTO;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * CryptoAgentService.analyzeStream() 的返回值封装。
 * 同时携带 DeepSeek 完整文字回复和解析出的结构化分析卡片。
 * 模块：Agent
 */
@Data
@AllArgsConstructor
public class AgentResult {

    /** DeepSeek 流式输出的完整文字回复，用于持久化到 chat_message 表 */
    private String content;

    /**
     * 从完整回复中解析的结构化分析卡片。
     * 仅分析类问题才携带，普通问答为 null。
     */
    private AnalysisCardDTO analysisCard;
}
