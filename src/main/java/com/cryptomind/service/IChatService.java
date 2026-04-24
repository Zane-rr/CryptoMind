package com.cryptomind.service;

import com.cryptomind.dto.ChatRequest;
import com.cryptomind.dto.HistoryResponse;
import com.cryptomind.entity.ChatMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * Chat模块服务接口，定义会话管理、消息持久化和对话处理的标准契约。
 * 模块：Chat
 */
public interface IChatService {

    /**
     * 获取或创建会话。sessionId为空时自动生成UUID并持久化，
     * 不为空时检查是否存在，不存在则新建（防止客户端传入无效ID）。
     *
     * @param sessionId 客户端传入的会话ID，可为null或空字符串
     * @return 有效的sessionId
     */
    String getOrCreateSession(String sessionId);

    /**
     * 查询指定会话的全部历史消息。
     *
     * @param sessionId 会话唯一标识
     * @return 会话历史响应DTO
     */
    HistoryResponse getHistory(String sessionId);

    /**
     * 清空指定会话的历史消息，同时删除Redis缓存。
     *
     * @param sessionId 会话唯一标识
     */
    void clearHistory(String sessionId);

    /**
     * 保存一条消息到MySQL，并更新Redis缓存。
     * 先写DB再更新缓存，保证持久化成功后缓存才被刷新。
     *
     * @param sessionId 会话唯一标识
     * @param role      消息角色：user 或 assistant
     * @param content   消息内容
     * @return 已保存的消息实体
     */
    ChatMessage saveMessage(String sessionId, String role, String content);

    /**
     * 获取上下文消息列表，优先从Redis读取，缓存不存在时回退到MySQL。
     * 限制最近20条，防止超出LLM的token上限。
     *
     * @param sessionId 会话唯一标识
     * @return 用于拼接Prompt的消息列表
     */
    List<ChatMessage> getContextMessages(String sessionId);

    /**
     * 处理用户提问，通过SseEmitter流式推送响应。
     * 输入状态：ChatRequest（含sessionId、question）
     * 输出状态：通过SseEmitter流式推送ChatResponse事件
     *
     * @param request 用户提问请求
     * @param emitter SSE发射器，用于流式推送响应
     */
    void ask(ChatRequest request, SseEmitter emitter);
}
