# Agent模块开发文档

## 1. 模块定位

替换`ChatServiceImpl.ask()`里的mock，实现完整的加密货币分析Agent。
负责：数据聚合（行情+新闻）→ DeepSeek流式分析 → 结构化卡片解析 → 分析记录入库。

---

## 2. 新增文件清单

修改文件：
- `service/impl/ChatServiceImpl.java` — 替换mock，接入Agent

新增文件：
- `agent/CryptoAgentService.java` — Agent主入口
- `agent/AgentResult.java` — Agent返回值封装
- `config/ChatClientConfig.java` — ChatClient Bean配置

---

## 3. ChatClientConfig.java

路径：`config/ChatClientConfig.java`

- 注解：`@Configuration`
- 注册`ChatClient` Bean，复用已有的`spring.ai.openai.*`配置（DeepSeek兼容OpenAI接口）
- 注入`ChatModel`（Spring AI自动配置），用`ChatClient.builder(chatModel).build()`构建

---

## 4. AgentResult.java

路径：`agent/AgentResult.java`

- 注解：`@Data` `@AllArgsConstructor`
- 字段：
    - `content: String` — DeepSeek的完整文字回复
    - `analysisCard: AnalysisCardDTO` — 解析出的结构化卡片，普通问答为null

---

## 5. CryptoAgentService.java

路径：`agent/CryptoAgentService.java`

- 注解：`@Service` `@Slf4j` `@RequiredArgsConstructor`
- 注入：`ChatClient` `IMarketService` `INewsService` `ObjectMapper`

### System Prompt
你是CryptoMind，一个专业的加密货币分析助手。
只回答与加密货币、区块链、数字资产相关的问题。
对于无关问题，礼貌拒绝并引导用户提问加密货币相关内容。
所有回答必须用中文。
分析时要客观，数据说话，必须提示投资风险。
不要对任何投资结果负责，每次分析末尾加免责声明。

### 核心公开方法public AgentResult analyzeStream(
String question,
String symbol,
List<ChatMessage> contextMessages,
SseEmitter emitter,
String sessionId,
ObjectMapper objectMapper
)

### 工作流程

**Step1：立刻推送状态提示**
- 构造`ChatResponse`，content="正在获取市场数据，请稍候..."，analysisCard=null
- `emitter.send()`推送给前端

**Step2：resolveSymbol() — 提取币种**
- symbol不为空 → 标准化（转大写，不含USDT则补上）
- symbol为空 → 从question关键词匹配：
    - BTC / 比特币 → BTCUSDT
    - ETH / 以太 → ETHUSDT
    - SOL / 索拉纳 → SOLUSDT
    - BNB → BNBUSDT
    - 其他 → null

**Step3：fetchMarketData() — 拉行情+指标**
- symbol为null → 直接返回字符串"未指定币种，无法获取行情数据"
- 调`marketService.getPrice(symbol)`
- 调`marketService.getIndicators(symbol, "1h")`
- 序列化为可读字符串返回
- 任何异常降级返回"行情数据获取失败"，不抛异常不中断流程

**Step4：fetchNews() — 搜索相关新闻**
- 构造`NewsSearchRequest(query=question, symbol=symbol去掉USDT后缀, limit=5)`
- 调`newsService.searchNews(req)`
- 格式化为`"- title\n  summary\n"`字符串返回
- 结果为空返回"暂无相关新闻"
- 任何异常降级返回"新闻数据获取失败"，不抛异常不中断流程

**Step5：buildPrompt() — 构建用户Prompt**
内容模板：
用户问题：{question}
【实时行情数据】
{marketData}
【相关新闻】
{newsData}
请基于以上数据回答用户问题。
如果是分析类问题，请在回答末尾附上如下格式的JSON分析卡片：
```json
{
"symbol": "BTCUSDT",
"suggestion": "买入/卖出/观望",
"confidence": 0到100的整数,
"technicalSummary": "技术面分析摘要",
"newsSummary": "新闻面分析摘要",
"riskAdvice": "风险提示"
}

**Step6：buildMessages() — 构建消息列表**
- 第一条：`SystemMessage(SYSTEM_PROMPT)`
- 中间：历史上下文取最近10条，user→`UserMessage`，assistant→`AssistantMessage`
- 最后：`UserMessage(buildPrompt结果)`

**Step7：流式调用DeepSeek**
- `chatClient.prompt().messages(messages).stream().content()`
- 逐个chunk推送给emitter，构造`ChatResponse(sessionId, role=assistant, content=chunk)`
- 同时用`StringBuilder`累积完整回复
- 调用失败：推送错误提示，`emitter.completeWithError()`

**Step8：tryParseCard() — 解析分析卡片**
- 从完整回复里找` ```json ... ``` `块
- `objectMapper`解析为`AnalysisCardDTO`
- 解析失败返回null，不抛异常

**Step9：返回AgentResult**
- `return new AgentResult(fullContent, card)`

### 异常处理原则
- Market/News获取失败：降级返回提示字符串，不中断流程
- DeepSeek调用失败：推送错误提示，`emitter.completeWithError()`
- 卡片解析失败：card=null，不影响文字回复

---

## 6. ChatServiceImpl修改点

### 新增注入
```java
private final CryptoAgentService cryptoAgentService;
```

### ask()方法替换逻辑

原来的mock代码：
```java
// TODO: [Agent集成] 此处替换为LangGraph4J Agent调用，传入contextMessages
String mockContent = "Agent功能开发中，sessionId: " + sessionId;
```

替换为以下逻辑：

1. 调用`cryptoAgentService.analyzeStream(question, symbol, contextMessages, emitter, sessionId, objectMapper)`
2. `analyzeStream`内部负责SSE推送（状态提示+流式chunk）
3. `analyzeStream`返回`AgentResult`
4. `saveMessage(sessionId, "user", request.getQuestion())`
5. `saveMessage(sessionId, "assistant", agentResult.getContent())`
6. 如果`agentResult.getAnalysisCard() != null`，存`analysis_record`表：
    - sessionId / symbol / suggestion / confidence
    - technicalSummary / newsSummary / riskAdvice
    - fullResponse = agentResult.getContent()
    - `analysisRecordRepository.save(record)`
7. 推送done事件，`emitter.complete()`

注意：`saveMessage`和`analysisRecord`入库在`analyzeStream`完成后执行，不在`analyzeStream`内部执行。

---

## 7. SSE事件格式

**第一个事件（状态提示，analyzeStream内部推送）**
```json
event: message
data: {"sessionId":"xxx","role":"assistant","content":"正在获取市场数据，请稍候...","analysisCard":null}
```

**流式chunk（每个DeepSeek输出片段，analyzeStream内部推送）**
```json
event: message
data: {"sessionId":"xxx","role":"assistant","content":"chunk内容","analysisCard":null}
```

**最后done事件（携带messageId和完整卡片，ChatServiceImpl推送）**
```json
event: done
data: {"sessionId":"xxx","messageId":123,"role":"assistant","content":"","analysisCard":{...}}
```

---

## 8. 完整工作流程图
```用户问题
↓
立刻推送"正在获取市场数据，请稍候..."
↓
resolveSymbol()  —  提取/标准化币种
↓
fetchMarketData()  —  拉行情+技术指标
fetchNews()        —  搜索相关新闻
（顺序执行，失败降级不中断）
↓
buildPrompt() + buildMessages()
↓
DeepSeek流式输出 → 逐chunk推送SSE
↓
tryParseCard()  —  解析结构化卡片
↓
返回AgentResult
↓
saveMessage(user) + saveMessage(assistant)
↓
saveAnalysisRecord()（card不为null时）
↓
推送done事件，emitter.complete()
```

----

## 9. 开发规范

- 面向接口编程，注入`IMarketService` `INewsService`而非实现类
- 禁止在循环内调用外部API
- 所有外部调用有try-catch，失败降级不抛异常（Market/News）
- 日志用`@Slf4j`，关键步骤打info，异常打warn/error
- 禁止`System.out.println`
