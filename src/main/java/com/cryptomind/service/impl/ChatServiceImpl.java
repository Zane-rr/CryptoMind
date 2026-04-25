package com.cryptomind.service.impl;

import com.cryptomind.agent.AgentResult;
import com.cryptomind.agent.CryptoAgentService;
import com.cryptomind.dto.AnalysisCardDTO;
import com.cryptomind.dto.ChatMessageDTO;
import com.cryptomind.dto.ChatRequest;
import com.cryptomind.dto.ChatResponse;
import com.cryptomind.dto.HistoryResponse;
import com.cryptomind.entity.AnalysisRecord;
import com.cryptomind.entity.ChatMessage;
import com.cryptomind.entity.ChatSession;
import com.cryptomind.repository.AnalysisRecordRepository;
import com.cryptomind.repository.ChatMessageRepository;
import com.cryptomind.repository.ChatSessionRepository;
import com.cryptomind.service.IChatService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * IChatService的默认实现，负责会话管理、消息持久化和上下文缓存。
 * 模块：Chat
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements IChatService {

    /** Redis key前缀，格式：chat:session:{sessionId} */
    private static final String REDIS_KEY_PREFIX = "chat:session:";

    /** Redis缓存过期时间：24小时 */
    private static final Duration REDIS_TTL = Duration.ofHours(24);

    /** 会话标题最大截取长度 */
    private static final int TITLE_MAX_LENGTH = 20;

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final AnalysisRecordRepository analysisRecordRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CryptoAgentService cryptoAgentService;

    /**
     * {@inheritDoc}
     */
    @Override
    public String getOrCreateSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return createNewSession();
        }

        boolean exists = chatSessionRepository.findBySessionId(sessionId).isPresent();
        if (!exists) {
            // 客户端传入了一个不存在的sessionId，重新创建以保证数据一致性
            log.warn("sessionId不存在，将创建新会话：{}", sessionId);
            return createNewSession();
        }
        return sessionId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public HistoryResponse getHistory(String sessionId) {
        List<ChatMessage> messages = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);

        List<ChatMessageDTO> messageDTOs = messages.stream()
                .map(this::toMessageDTO)
                .collect(Collectors.toList());

        HistoryResponse response = new HistoryResponse();
        response.setSessionId(sessionId);
        response.setMessages(messageDTOs);
        return response;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void clearHistory(String sessionId) {
        chatMessageRepository.deleteBySessionId(sessionId);

        // 同步清除Redis缓存，防止脏数据被下次请求读取
        redisTemplate.delete(REDIS_KEY_PREFIX + sessionId);
        log.info("已清空会话历史，sessionId={}", sessionId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ChatMessage saveMessage(String sessionId, String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        ChatMessage saved = chatMessageRepository.save(message);

        // 更新Redis缓存：重新加载最近20条写入，保证缓存与DB一致
        refreshRedisCache(sessionId);

        // 若是第一条用户消息，更新会话标题
        if ("user".equals(role)) {
            updateTitleIfAbsent(sessionId, content);
        }

        return saved;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ChatMessage> getContextMessages(String sessionId) {
        String redisKey = REDIS_KEY_PREFIX + sessionId;
        String cached = redisTemplate.opsForValue().get(redisKey);

        if (cached != null) {
            try {
                return objectMapper.readValue(cached, new TypeReference<List<ChatMessage>>() {});
            } catch (Exception e) {
                // 缓存反序列化失败时降级到MySQL，避免服务中断
                log.warn("Redis缓存反序列化失败，降级到MySQL，sessionId={}，原因：{}", sessionId, e.getMessage());
            }
        }

        return chatMessageRepository.findTop20BySessionIdOrderByCreatedAtAsc(sessionId);
    }

    /**
     * {@inheritDoc}
     * 工作流：先取历史上下文 → Agent聚合数据+流式分析（内部推送状态提示和chunk）
     * → 分析完成后持久化user/assistant消息 → 有卡片则入库analysis_record
     * → 推送done事件携带messageId和完整卡片 → emitter.complete()。
     * 注意：saveMessage在analyzeStream完成后执行，避免当前问题被重复计入上下文。
     */
    @Override
    public void ask(ChatRequest request, SseEmitter emitter) {
        try {
            String sessionId = getOrCreateSession(request.getSessionId());

            // 先取历史上下文，再调用Agent，防止当前问题被重复计入上下文
            List<ChatMessage> contextMessages = getContextMessages(sessionId);
            log.debug("上下文消息条数：{}，sessionId={}", contextMessages.size(), sessionId);

            // Agent内部负责：状态提示推送、数据聚合、DeepSeek流式输出和chunk推送
            AgentResult agentResult = cryptoAgentService.analyzeStream(
                    request.getQuestion(), request.getSymbol(),
                    contextMessages, emitter, sessionId, objectMapper);

            // 分析完成后持久化用户消息和助手回复
            saveMessage(sessionId, "user", request.getQuestion());
            ChatMessage assistantMsg = saveMessage(sessionId, "assistant", agentResult.getContent());

            // 有分析卡片时入库 analysis_record，供历史记录和审计使用
            if (agentResult.getAnalysisCard() != null) {
                AnalysisCardDTO card = agentResult.getAnalysisCard();
                AnalysisRecord record = new AnalysisRecord();
                record.setSessionId(sessionId);
                record.setSymbol(card.getSymbol());
                record.setSuggestion(card.getSuggestion());
                record.setConfidence(card.getConfidence());
                record.setTechnicalSummary(card.getTechnicalSummary());
                record.setNewsSummary(card.getNewsSummary());
                record.setRiskAdvice(card.getRiskAdvice());
                record.setFullResponse(agentResult.getContent());
                analysisRecordRepository.save(record);
            }

            // 推送done事件，携带数据库生成的messageId和完整分析卡片
            ChatResponse doneResponse = new ChatResponse();
            doneResponse.setType("DONE");
            doneResponse.setSessionId(sessionId);
            doneResponse.setMessageId(assistantMsg.getId());
            doneResponse.setRole("assistant");
            doneResponse.setContent("");
            doneResponse.setAnalysisCard(agentResult.getAnalysisCard());
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(objectMapper.writeValueAsString(doneResponse)));
            emitter.complete();
        } catch (IOException e) {
            log.error("SSE推送失败，原因：{}", e.getMessage());
            // analyzeStream可能已调用completeWithError，忽略重复调用的异常
            try { emitter.completeWithError(e); } catch (Exception ignored) {}
        } catch (Exception e) {
            log.error("ask处理异常，原因：{}", e.getMessage(), e);
            // analyzeStream可能已调用completeWithError，忽略重复调用的异常
            try { emitter.completeWithError(e); } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // 私有辅助方法
    // -------------------------------------------------------------------------

    /**
     * 创建新会话并持久化到MySQL。
     *
     * @return 新生成的sessionId
     */
    private String createNewSession() {
        String newSessionId = UUID.randomUUID().toString();
        ChatSession session = new ChatSession();
        session.setSessionId(newSessionId);
        chatSessionRepository.save(session);
        log.info("已创建新会话，sessionId={}", newSessionId);
        return newSessionId;
    }

    /**
     * 将最近20条消息序列化后写入Redis，并重置过期时间。
     *
     * @param sessionId 会话唯一标识
     */
    private void refreshRedisCache(String sessionId) {
        try {
            List<ChatMessage> recent = chatMessageRepository.findTop20BySessionIdOrderByCreatedAtAsc(sessionId);
            String json = objectMapper.writeValueAsString(recent);
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + sessionId, json, REDIS_TTL);
        } catch (Exception e) {
            // 缓存刷新失败不影响主流程，仅记录日志
            log.warn("Redis缓存刷新失败，sessionId={}，原因：{}", sessionId, e.getMessage());
        }
    }

    /**
     * 若会话标题尚未设置，取消息前N字作为标题。
     *
     * @param sessionId 会话唯一标识
     * @param content   用户消息内容
     */
    private void updateTitleIfAbsent(String sessionId, String content) {
        chatSessionRepository.findBySessionId(sessionId).ifPresent(session -> {
            if (session.getTitle() == null || session.getTitle().isBlank()) {
                String title = content.length() > TITLE_MAX_LENGTH
                        ? content.substring(0, TITLE_MAX_LENGTH)
                        : content;
                session.setTitle(title);
                chatSessionRepository.save(session);
            }
        });
    }

    /**
     * 将ChatMessage实体转换为ChatMessageDTO。
     *
     * @param message 消息实体
     * @return 消息DTO
     */
    private ChatMessageDTO toMessageDTO(ChatMessage message) {
        ChatMessageDTO dto = new ChatMessageDTO();
        dto.setId(message.getId());
        dto.setRole(message.getRole());
        dto.setContent(message.getContent());
        dto.setCreatedAt(message.getCreatedAt());
        return dto;
    }
}
