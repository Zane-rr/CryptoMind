package com.cryptomind.repository;

import com.cryptomind.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * ChatMessage 数据访问层。
 * 模块：Chat
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /**
     * 查询指定会话的全部消息，按时间正序排列。
     *
     * @param sessionId 会话唯一标识
     * @return 消息列表（升序）
     */
    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /**
     * 查询指定会话最近20条消息，按时间正序排列，用于拼接上下文窗口。
     * 取最近20条而非全量，是为了防止超出LLM的token限制。
     *
     * @param sessionId 会话唯一标识
     * @return 最近20条消息（升序）
     */
    List<ChatMessage> findTop20BySessionIdOrderByCreatedAtAsc(String sessionId);

    /**
     * 删除指定会话的全部消息。
     *
     * @param sessionId 会话唯一标识
     */
    void deleteBySessionId(String sessionId);
}
