package com.cryptomind.dto;

import lombok.Data;

/**
 * 对话响应DTO，SSE流式返回时每个事件携带此结构。
 * 模块：Chat
 */
@Data
public class ChatResponse {

    /**
     * 消息类型：HEARTBEAT（节点日志）/ CONTENT（流式文字chunk）/ DONE（完成事件）
     * 前端根据此字段路由渲染逻辑。
     */
    private String type;

    /** 会话ID，首次创建时由服务端生成 */
    private String sessionId;

    /** 消息数据库主键 */
    private Long messageId;

    /** 消息角色：user 或 assistant */
    private String role;

    /** 消息内容 */
    private String content;

    /**
     * 分析结果卡片，仅分析类问题才携带，普通问答为null。
     * 前端根据此字段是否存在决定是否渲染分析卡片UI。
     */
    private AnalysisCardDTO analysisCard;
}
