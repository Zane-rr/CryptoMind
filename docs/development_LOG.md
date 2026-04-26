 # CryptoMind 开发进度日志

---

## 2026-04-25 — Function Calling + Multi-Agent 模块

### 背景
在现有 `CryptoAgentService` 基础上引入 Spring AI Function Calling，并实现多角色并行辩论机制（情报员 → 三专家 → CIO裁决）。设计文档：`docs/FUNCTION_CALLING_MULTIAGENT_MODULE.md`。

### 新增文件

#### `agent/MarketTools.java`
- `@Configuration` Bean注册中心，注册4个 `Function<Request, String>` Bean
- **getPrice** — 实时价格和24h涨跌幅
- **getIndicators** — RSI / MACD / 布林带（固定1h周期）
- **searchNews** — ES向量语义检索，取5条新闻
- **getPortfolio** — 账户持仓（当前阶段返回空列表）
- 每个Bean配套 `record` 定义请求参数类型
- 使用 `org.springframework.context.annotation.Description` 注解描述函数用途

### 修改文件

#### `agent/CryptoAgentService.java` — 全面重构

**新增5个 System Prompt 常量：**
- `SYSTEM_PROMPT_INTELLIGENCE_OFFICER` — 情报员，带Tool权限收集数据
- `SYSTEM_PROMPT_BULLISH` — 多头分析师，输出JSON
- `SYSTEM_PROMPT_RISK` — 风控师，输出JSON
- `SYSTEM_PROMPT_QUANT` — 量化策略师，输出JSON
- `SYSTEM_PROMPT_CIO` — 首席投资官，综合裁决输出JSON

**新增内部类：**
- `ExpertReport` — 单专家报告（role, verdict, confidence, reasoning, rawJson）
- `CIOResult` — CIO裁决结果（score, finalVerdict, bestRole, summary, 四个价格字段）

**新增方法：**
- `gatherIntelligence(question, sessionId)` — 情报员角色，带Tool权限非流式调用
- `callExpert(systemPrompt, brief, role)` — 单专家分析，内部捕获所有异常，CompletableFuture永不以异常完成
- `callCIO(bullish, risk, quant)` — CIO汇总裁决，解析失败取最高confidence专家降级
- `runMultiAgentDebate(brief, question)` — 两轮辩论编排 + 保底逻辑
- `defaultReport(role)` — 超时兜底用默认ExpertReport

**QUICK分支改造：**
- 移除硬编码按 `dataNeeded` 拉数据的逻辑
- 改为 `chatClient.prompt().functions(...).messages(...).call()` 非流式Tool Calling
- LLM自主决定调哪些工具

**DEEP分支改造：**
- 原有 `fetchMarketData()` + `fetchNews()` + `buildPrompt()` 路径废弃
- 新流程：`gatherIntelligence()` → `runMultiAgentDebate()` → 构建finalPrompt → 流式输出

**删除内容：**
- `fetchMarketData()` 方法
- `fetchNews()` 方法
- `buildPrompt()` 方法
- `IntentResult.dataNeeded` 字段
- `extractIntentByLLM()` 中 `dataNeeded` 相关的Prompt描述和解析逻辑
- `IMarketService` / `INewsService` 的直接注入（现由 `MarketTools` 持有）

**鲁棒性优化（超出文档要求）：**
1. `callExpert()` 和 `callCIO()` 中均增加markdown代码块剥离逻辑（正则兼容 `` ```json `` 标签），防止LLM自作主张附加围栏导致JSON解析失败
2. `ForkJoinPool.commonPool()` 超时后使用 `getNow(defaultReport(...))` 取已完成结果，未完成用降级值，不阻断流程
3. QUICK分支 `functions()` 声明严格位于 `.call()` 之前，Bean名称与 `MarketTools` 中 `@Bean` 方法名完全一致

### DEEP分支完整LLM调用链（正常路径7次，第二轮最多9次）
```
用户问题
 └─ extractIntentByLLM()          [第1次]
 └─ gatherIntelligence()          [第2次，带Tool Calling]
 └─ 三专家并行 (CompletableFuture) [第3/4/5次]
 └─ callCIO()                     [第6次]
 └─ 流式最终输出                   [第7次]
```

### 待跟进
- `TaskExecutor` 专用线程池：当前使用 `ForkJoinPool.commonPool()`，并发用户多时可能因大量阻塞HTTP调用导致线程枯竭。后续可在 `ChatClientConfig` 中自定义专用 `TaskExecutor` 并传入 `CompletableFuture.supplyAsync(task, executor)`。
- Exchange模块完成后，`getPortfolio` Bean将返回真实持仓数据。

---

## 2026-04-25 — LangGraph4J API修复（编译通过）

### 问题
`DebateGraph.buildGraph()` 编译报错：`addConditionalEdges` 的 lambda 类型不匹配，`build()` 方法不存在。

### 根因
通过 `javap` 反编译 `langgraph4j-core-1.5.14.jar` 确认实际 API：
- `addNode` 第二参数要求 `AsyncNodeAction<S>`（即 `Function<S, CompletableFuture<Map>>`），不能直接传方法引用
- `addConditionalEdges` 第二参数要求 `AsyncEdgeAction<S>`（即 `Function<S, CompletableFuture<String>>`），不能传裸 lambda
- 图编译方法为 `compile()`，不存在 `build()`

### 修复内容（`agent/DebateGraph.java`）
- 新增 import：`AsyncNodeAction`、`AsyncEdgeAction`（`org.bsc.langgraph4j.action.*`）
- 三个 `addNode` 调用改为 `AsyncNodeAction.node_async(this::nodeMethod)` 包装
- 条件边改为 `AsyncEdgeAction.edge_async(state -> ...)` 包装（内部使用同步 `EdgeAction`，可 throws Exception）
- 条件边返回值和 Map key 由字符串 `"END"` 改为 `StateGraph.END` 常量，避免值不匹配
- `.build()` 改为 `.compile()`

### 验证
人工检测通过，无编译错误。

---

## 2026-04-25 — @Tool重构 + LangGraph4J辩论图接入

### 背景
将 MarketTools 从 Spring AI Function Calling（`@Bean`+`@Description`+`Function<>`）迁移到 `@Tool` 注解方式；引入 LangGraph4J 有向有环图替代上一轮的手写 CompletableFuture 编排，实现更清晰的多Agent辩论状态流转。设计文档：`docs/DEEP_MULTIAGENT_MODULE.md`。

### 修改文件

#### `agent/MarketTools.java`
- 去除 `@Configuration`，改为 `@Component`
- 去除所有 `@Bean`、`@Description`、`Function<Request, String>` 写法
- 改为普通方法 + `@Tool("描述")` 注解（`org.springframework.ai.tool.annotation.Tool`）
- 去除4个 `record` 请求类型定义

#### `agent/CryptoAgentService.java`
- 新增注入：`MarketTools marketTools`、`DebateGraph debateGraph`
- QUICK分支和 `gatherIntelligence()` 中将 `.functions(...)` 改为 `.tools(marketTools)`
- DEEP分支替换为 LangGraph4J 调用：
  1. `gatherIntelligence()` — 情报员收集数据
  2. 构建 `initData` Map → `debateGraph.buildGraph().invoke(initData).get()`
  3. 解析 `finalState.getCioResult()` 中的 JSON 字段
  4. 构建含 `weightInsight` 的 `finalPrompt` → 流式输出
- 删除旧的 `runMultiAgentDebate()`、`callExpert()`、`callCIO()`、`defaultReport()` 方法
- 删除 `ExpertReport`、`CIOResult` 内部类
- 删除已迁移到 `DebateGraph` 的4个 System Prompt 常量（BULLISH/RISK/QUANT/CIO）

### 新增文件

#### `agent/DebateState.java`
- 继承 `org.bsc.langgraph4j.state.AgentState`，本质是 Map 包装
- 字段（getter）：brief / question / bullishReport / riskReport / quantReport / crossExamination / bullishRebuttal / riskRebuttal / quantRebuttal / cioResult / score / round

#### `agent/DebateGraph.java`
- `@Component`，注入 `ChatClient` + `ObjectMapper`
- **5个 System Prompt 常量**：BULLISH / RISK / QUANT / CIO_CROSS / CIO_FINAL（相比旧版新增 weights 字段和 CIO质询模式）
- **3个图节点方法**（`throws Exception`）：
  - `expertDebateNode` — 三专家并行（ForkJoinPool.commonPool，30s超时降级）；第二轮附加 CIO 定向质询
  - `cioCrossNode` — CIO 提取矛盾，生成 JSON 质询
  - `cioFinalNode` — CIO 综合裁决，解析 score，round 自增
- **图结构**：`START → expert_debate → cio_cross → cio_final →条件边→ END 或 expert_debate`
- 条件边：`score≥80 或 round>MAX_DEBATE_ROUNDS → END，否则 → expert_debate`
- **3个私有辅助**：`callLLM()`（含markdown围栏剥离）、`parseScore()`、`extractQuestion()`

### DEEP分支完整LLM调用链（正常路径最少7次）
```
extractIntentByLLM()                    [第1次]
gatherIntelligence() + Tool Calls       [第2次+n次工具调用]
DebateGraph — Round 1:
  expertDebateNode (3专家并行)           [第3/4/5次]
  cioCrossNode                          [第6次]
  cioFinalNode                          [第7次]
  → score≥80 → 流式最终输出             [第8次]
  → score<80 → Round 2 (同上+3次)      [最多第9/10/11/12次]
```

### 注意事项
- `buildGraph()` 每次请求调用，不缓存（State有状态）
- `addConditionalEdges` 使用显式 Map 映射 `"END" → StateGraph.END`，避免字符串歧义
- `callLLM()` 内部捕获所有异常返回 `"{}"`，节点永不以异常完成

---

## 历史模块完成记录

| 日期 | 提交 | 内容 |
|---|---|---|
| — | `3a4ee98` | chore: init CryptoMind project scaffold |
| — | `95206c5` | feat(chat): implement Chat module |
| — | `d194e26` | feat(market): implement Market module |
| — | `715a942` | feat(news): implement News module with Finnhub adapter and ES vector search |
