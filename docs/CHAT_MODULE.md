按照以下设计实现Chat模块的完整代码，包含数据库、实体、DTO、Controller、Service所有层：

## 1. 数据库表（JPA自动建表，不需要写SQL）

### ChatSession实体 (entity/ChatSession.java)
字段：
- id: Long, 主键自增
- sessionId: String, UUID, 唯一索引, 不可为空
- title: String, 会话标题（取第一条消息前20字）
- createdAt: LocalDateTime
- updatedAt: LocalDateTime

### ChatMessage实体 (entity/ChatMessage.java)
字段：
- id: Long, 主键自增
- sessionId: String, 索引
- role: String ("user" 或 "assistant")
- content: String, TEXT类型
- createdAt: LocalDateTime

### AnalysisRecord实体 (entity/AnalysisRecord.java)
字段：
- id: Long, 主键自增
- sessionId: String, 索引
- symbol: String (如 BTCUSDT)
- suggestion: String (买入/卖出/观望)
- confidence: Integer (0-100)
- technicalSummary: String, TEXT
- newsSummary: String, TEXT
- riskAdvice: String, TEXT
- fullResponse: String, TEXT
- createdAt: LocalDateTime

## 2. Repository (repository/)
- ChatSessionRepository extends JpaRepository
    - findBySessionId(String sessionId)
- ChatMessageRepository extends JpaRepository
    - findBySessionIdOrderByCreatedAtAsc(String sessionId)
    - findTop20BySessionIdOrderByCreatedAtAsc(String sessionId)
- AnalysisRecordRepository extends JpaRepository
    - findBySessionIdOrderByCreatedAtDesc(String sessionId)

## 3. DTO (dto/)

### 请求DTO
ChatRequest:
- sessionId: String (可为空，空则创建新会话)
- question: String (用户问题)
- symbol: String (可为空，Agent自动识别)

### 响应DTO
ChatResponse:
- sessionId: String
- messageId: Long
- role: String
- content: String
- analysisCard: AnalysisCardDTO (可为空，只有分析类问题才有)

AnalysisCardDTO:
- symbol: String
- suggestion: String
- confidence: Integer
- technicalSummary: String
- newsSummary: String
- riskAdvice: String

HistoryResponse:
- sessionId: String
- messages: List<ChatMessageDTO>

ChatMessageDTO:
- id: Long
- role: String
- content: String
- createdAt: LocalDateTime

## 4. Controller (controller/ChatController.java)
注意：
- 类上加@RestController、@RequestMapping("/api/chat")、@Slf4j
- 加Swagger注解@Tag(name = "Chat", description = "对话管理")

接口：
1. POST /api/chat/ask
    - 返回SseEmitter（流式输出）
    - 入参：@RequestBody ChatRequest
    - 加@Operation(summary = "用户提问，SSE流式返回")

2. GET /api/chat/{sessionId}/history
    - 返回ResponseEntity<HistoryResponse>
    - 加@Operation(summary = "获取会话历史")

3. DELETE /api/chat/{sessionId}/history
    - 返回ResponseEntity<Void>
    - 加@Operation(summary = "清空会话历史")

## 5. Service (service/ChatService.java)
注意：
- 加@Service、@Slf4j
- 注入ChatSessionRepository、ChatMessageRepository、AnalysisRecordRepository、RedisTemplate

方法：
1. getOrCreateSession(String sessionId): String
    - sessionId为空则生成UUID，存入MySQL，返回sessionId
    - sessionId不为空则查询是否存在，不存在则创建

2. getHistory(String sessionId): HistoryResponse
    - 从MySQL查询消息列表
    - 转换为HistoryResponse返回

3. clearHistory(String sessionId): void
    - 删除MySQL中该sessionId的所有消息
    - 删除Redis中该sessionId的缓存

4. saveMessage(String sessionId, String role, String content): ChatMessage
    - 存入MySQL
    - 同时更新Redis缓存（key: chat:session:{sessionId}，过期24小时）

5. getContextMessages(String sessionId): List<ChatMessage>
    - 优先从Redis取（key: chat:session:{sessionId}）
    - Redis没有则从MySQL取最近20条
    - 返回消息列表用于拼接Prompt

6. ask(ChatRequest request, SseEmitter emitter): void
    - 调用getOrCreateSession获取sessionId
    - 调用getContextMessages获取历史
    - 暂时先返回一个mock响应："Agent功能开发中，sessionId: {sessionId}"
    - 通过emitter.send()流式发送
    - 调用saveMessage保存消息
    - 最后emitter.complete()

所有类都需要完整的Javadoc注释，遵循CLAUDE.md中的代码规范。