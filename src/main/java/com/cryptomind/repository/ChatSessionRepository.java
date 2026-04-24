package com.cryptomind.repository;

import com.cryptomind.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * ChatSession 数据访问层。
 * 模块：Chat
 */
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    /**
     * 根据 sessionId 查询会话。
     *
     * @param sessionId 会话唯一标识
     * @return 会话实体，不存在则为空
     */
    Optional<ChatSession> findBySessionId(String sessionId);
}
