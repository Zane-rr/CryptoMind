package com.cryptomind.controller;

import com.cryptomind.dto.ChatRequest;
import com.cryptomind.dto.HistoryResponse;
import com.cryptomind.service.IChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;

/**
 * Chat模块REST控制器，提供对话、历史查询和历史清空接口。
 * 模块：Chat
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Tag(name = "Chat", description = "对话管理")
public class ChatController {

    /** SSE连接超时时间：5分钟，给Agent足够的处理时间 */
    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    private final IChatService chatService;

    /**
     * 用户提问，SSE流式返回助手回复。
     * 异步执行防止SSE长连接占用Web容器线程。
     *
     * @param request 用户提问请求，含sessionId（可选）、question、symbol（可选）
     * @return SseEmitter，客户端监听message事件获取ChatResponse JSON
     */
    @Operation(summary = "用户提问，SSE流式返回")
    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        // 异步处理，避免阻塞Servlet线程
        CompletableFuture.runAsync(() -> chatService.ask(request, emitter));

        return emitter;
    }

    /**
     * 获取指定会话的历史消息列表。
     *
     * @param sessionId 会话唯一标识
     * @return 会话历史响应DTO
     */
    @Operation(summary = "获取会话历史")
    @GetMapping("/{sessionId}/history")
    public ResponseEntity<HistoryResponse> getHistory(@PathVariable String sessionId) {
        HistoryResponse history = chatService.getHistory(sessionId);
        return ResponseEntity.ok(history);
    }

    /**
     * 清空指定会话的历史消息（同时清除Redis缓存）。
     *
     * @param sessionId 会话唯一标识
     * @return 204 No Content
     */
    @Operation(summary = "清空会话历史")
    @ApiResponse(responseCode = "204", description = "清空成功，无返回体")
    @DeleteMapping("/{sessionId}/history")
    public ResponseEntity<Void> clearHistory(@PathVariable String sessionId) {
        chatService.clearHistory(sessionId);
        return ResponseEntity.noContent().build();
    }
}
