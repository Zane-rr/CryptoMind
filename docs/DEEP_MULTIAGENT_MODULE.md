# DEEP意图 Multi-Agent辩论模块开发文档

## 1. 整体架构

DEEP意图分两个阶段：
- 阶段一（Spring AI）：情报员用@Tool收集数据，生成投研情报简报
- 阶段二（LangGraph4J）：多Agent辩论有向有环图，CIO裁决，不达标自动返工

---

## 2. 改造文件清单

新增文件：
- `agent/MarketTools.java` — Spring AI @Tool工具类
- `agent/DebateGraph.java` — LangGraph4J辩论图
- `agent/DebateState.java` — LangGraph4J图状态

修改文件：
- `agent/CryptoAgentService.java` — 编译错误修复 + DEEP分支接入新流程

---

## 3. MarketTools.java

路径：`agent/MarketTools.java`

注解：`@Component` `@RequiredArgsConstructor` `@Slf4j`

注入：`IMarketService` `INewsService` `ObjectMapper`

import：`org.springframework.ai.tool.annotation.Tool`

四个方法全部加`@Tool`注解：

```java
@Tool("获取加密货币实时价格和24小时涨跌幅，输入交易对如BTCUSDT")
public String getPrice(String symbol) {
    try {
        return objectMapper.writeValueAsString(marketService.getPrice(symbol));
    } catch (Exception e) {
        log.warn("getPrice失败，symbol={}，原因：{}", symbol, e.getMessage());
        return "价格数据获取失败";
    }
}

@Tool("获取加密货币技术指标，包含RSI、MACD、布林带，输入交易对如BTCUSDT")
public String getIndicators(String symbol) {
    try {
        return objectMapper.writeValueAsString(
            marketService.getIndicators(symbol, "1h"));
    } catch (Exception e) {
        log.warn("getIndicators失败，symbol={}，原因：{}", symbol, e.getMessage());
        return "技术指标获取失败";
    }
}

@Tool("搜索加密货币相关新闻，输入英文关键词效果最佳，如Bitcoin BTC crypto")
public String searchNews(String keywords) {
    try {
        NewsSearchRequest req = new NewsSearchRequest();
        req.setQuery(keywords);
        req.setSymbol(null);
        req.setLimit(5);
        NewsSearchResponse resp = newsService.searchNews(req);
        if (resp.getNews() == null || resp.getNews().isEmpty()) return "暂无相关新闻";
        StringBuilder sb = new StringBuilder();
        for (NewsDTO n : resp.getNews()) {
            sb.append("- ").append(n.getTitle()).append("\n");
            if (n.getSummary() != null) sb.append("  ").append(n.getSummary()).append("\n");
        }
        return sb.toString().trim();
    } catch (Exception e) {
        log.warn("searchNews失败，原因：{}", e.getMessage());
        return "新闻数据获取失败";
    }
}

@Tool("获取币安账户当前持仓情况，无需输入参数，传入空字符串即可")
public String getPortfolio(String ignored) {
    try {
        return objectMapper.writeValueAsString(marketService.getPortfolio());
    } catch (Exception e) {
        log.warn("getPortfolio失败，原因：{}", e.getMessage());
        return "持仓数据获取失败";
    }
}
```

---

## 4. DebateState.java

路径：`agent/DebateState.java`

LangGraph4J的State必须继承`AgentState`，本质是一个Map。

```java
public class DebateState extends AgentState {

    public DebateState(Map initData) {
        super(initData);
    }

    // 投研情报简报（情报员生成，所有专家共享）
    public String getBrief() {
        return (String) data().getOrDefault("brief", "");
    }

    // 用户原始问题
    public String getQuestion() {
        return (String) data().getOrDefault("question", "");
    }

    // 三份专家报告（JSON字符串）
    public String getBullishReport() {
        return (String) data().getOrDefault("bullishReport", "");
    }
    public String getRiskReport() {
        return (String) data().getOrDefault("riskReport", "");
    }
    public String getQuantReport() {
        return (String) data().getOrDefault("quantReport", "");
    }

    // CIO质询内容（抛给专家的矛盾点）
    public String getCrossExamination() {
        return (String) data().getOrDefault("crossExamination", "");
    }

    // 专家回应质询的补充报告
    public String getBullishRebuttal() {
        return (String) data().getOrDefault("bullishRebuttal", "");
    }
    public String getRiskRebuttal() {
        return (String) data().getOrDefault("riskRebuttal", "");
    }
    public String getQuantRebuttal() {
        return (String) data().getOrDefault("quantRebuttal", "");
    }

    // CIO最终裁决结果（JSON字符串）
    public String getCioResult() {
        return (String) data().getOrDefault("cioResult", "");
    }

    // CIO评分
    public int getScore() {
        return (int) data().getOrDefault("score", 0);
    }

    // 当前轮次（从1开始）
    public int getRound() {
        return (int) data().getOrDefault("round", 1);
    }
}
```

---

## 5. DebateGraph.java

路径：`agent/DebateGraph.java`

注解：`@Component` `@RequiredArgsConstructor` `@Slf4j`

注入：`ChatClient chatClient` `ObjectMapper objectMapper`

### 5.1 常量

```java
private static final int MAX_DEBATE_ROUNDS = 2;
private static final int PASS_SCORE = 80;
```

### 5.2 五个System Prompt常量

**SYSTEM_PROMPT_BULLISH**
你是一位激进的加密货币多头分析师。
基于情报简报，只寻找看涨信号和做多理由。
同时对以下5个维度按重要性分配权重（总和=100）：
价格趋势、RSI、MACD、新闻情绪、成交量
只返回如下JSON，不要任何其他内容：
{
"role": "BULLISH",
"verdict": "做多/观望",
"confidence": 0到100整数,
"keySignals": ["信号1", "信号2", "信号3"],
"entryPrice": "建议开仓价位",
"takeProfitTargets": "第一目标:xxx，第二目标:xxx",
"reasoning": "核心做多逻辑，2-3句话",
"weights": {
"priceTrend": 权重整数,
"rsi": 权重整数,
"macd": 权重整数,
"newsSentiment": 权重整数,
"volume": 权重整数
}
}

**SYSTEM_PROMPT_RISK**
你是一位极度谨慎的风险控制师。
基于情报简报，只寻找风险信号和爆仓风险。
同时对以下5个维度按重要性分配权重（总和=100）：
价格趋势、RSI、MACD、新闻情绪、成交量
只返回如下JSON，不要任何其他内容：
{
"role": "RISK",
"verdict": "高风险/中风险/低风险",
"confidence": 0到100整数,
"keyRisks": ["风险1", "风险2", "风险3"],
"stopLoss": "止损价位:xxx，跌破离场",
"positionSize": "建议仓位占总资金的xx%-xx%",
"reasoning": "核心风险逻辑，2-3句话",
"weights": {
"priceTrend": 权重整数,
"rsi": 权重整数,
"macd": 权重整数,
"newsSentiment": 权重整数,
"volume": 权重整数
}
}

**SYSTEM_PROMPT_QUANT**
你是一位只看数字的量化策略师。
基于情报简报，纯数字分析，计算具体交易参数。
同时对以下5个维度按重要性分配权重（总和=100）：
价格趋势、RSI、MACD、新闻情绪、成交量
只返回如下JSON，不要任何其他内容：
{
"role": "QUANT",
"verdict": "执行/等待",
"confidence": 0到100整数,
"riskRewardRatio": "盈亏比如1:2.5",
"entryPrice": "精确开仓价",
"stopLoss": "精确止损价",
"takeProfitTargets": "第一目标:xxx，第二目标:xxx",
"reasoning": "数字依据，2-3句话",
"weights": {
"priceTrend": 权重整数,
"rsi": 权重整数,
"macd": 权重整数,
"newsSentiment": 权重整数,
"volume": 权重整数
}
}

**SYSTEM_PROMPT_CIO_CROSS**
你是首席投资官（CIO），负责主持专家辩论。
阅读三位分析师的报告，提取最核心的矛盾点。
把矛盾点直接质询相关专家，要求他们正面回应。
只返回如下JSON，不要任何其他内容：
{
"contradictions": [
{
"targetRole": "BULLISH或RISK或QUANT",
"question": "质询问题，直接犀利，要求对方解释矛盾"
}
],
"weightConflict": "三人权重分配差异最大的维度是什么，一句话描述"
}

**SYSTEM_PROMPT_CIO_FINAL**
你是首席投资官（CIO），基于三位分析师的报告和质询回应做出最终裁决。
综合考虑：各方观点、质询回应、指标权重分配差异。
只返回如下JSON，不要任何其他内容：
{
"score": 0到100整数（综合置信度，≥80建议执行）,
"finalVerdict": "买入/卖出/观望",
"bestRole": "BULLISH或RISK或QUANT（最有说服力的角色）",
"summary": "综合结论，结论先行，3-5句话",
"entryPrice": "综合后建议开仓价",
"stopLoss": "综合后止损价",
"takeProfitTargets": "综合后止盈目标",
"positionSize": "综合后建议仓位",
"weightInsight": "三人权重分配的关键差异及其含义，一句话"
}

### 5.3 图节点方法

**expertDebateNode**
```java
public Map<String, Object> expertDebateNode(DebateState state) {
    String brief = state.getBrief();
    String crossExam = state.getCrossExamination(); // 第二轮时有质询内容

    // 构建每个专家的UserPrompt
    String basePrompt = "以下是投研情报简报，请进行分析：\n" + brief;
    String bullishPrompt = crossExam.isBlank() ? basePrompt :
        basePrompt + "\n\n【CIO质询，请正面回应】\n" +
        extractQuestion(crossExam, "BULLISH");
    // Risk和Quant同理

    // 并行调用三个专家
    CompletableFuture<String> bullishFuture = CompletableFuture.supplyAsync(() ->
        callLLM(SYSTEM_PROMPT_BULLISH, bullishPrompt));
    CompletableFuture<String> riskFuture = CompletableFuture.supplyAsync(() ->
        callLLM(SYSTEM_PROMPT_RISK, riskPrompt));
    CompletableFuture<String> quantFuture = CompletableFuture.supplyAsync(() ->
        callLLM(SYSTEM_PROMPT_QUANT, quantPrompt));

    CompletableFuture.allOf(bullishFuture, riskFuture, quantFuture)
        .get(30, TimeUnit.SECONDS);

    return Map.of(
        "bullishReport", bullishFuture.get(),
        "riskReport", riskFuture.get(),
        "quantReport", quantFuture.get(),
        "crossExamination", "" // 清空上一轮质询
    );
}
```

**cioCrossNode**
```java
public Map<String, Object> cioCrossNode(DebateState state) {
    String prompt = "三位分析师的报告如下，请提取矛盾点并质询：\n\n" +
        "【激进分析师】\n" + state.getBullishReport() + "\n\n" +
        "【风控师】\n" + state.getRiskReport() + "\n\n" +
        "【量化策略师】\n" + state.getQuantReport();

    String result = callLLM(SYSTEM_PROMPT_CIO_CROSS, prompt);
    return Map.of("crossExamination", result);
}
```

**cioFinalNode**
```java
public Map<String, Object> cioFinalNode(DebateState state) {
    String prompt = "三位分析师报告及质询回应如下，请做出最终裁决：\n\n" +
        "【激进分析师报告】\n" + state.getBullishReport() + "\n\n" +
        "【风控师报告】\n" + state.getRiskReport() + "\n\n" +
        "【量化策略师报告】\n" + state.getQuantReport() + "\n\n" +
        "【质询记录】\n" + state.getCrossExamination();

    String result = callLLM(SYSTEM_PROMPT_CIO_FINAL, prompt);
    int score = parseScore(result);

    return Map.of(
        "cioResult", result,
        "score", score,
        "round", state.getRound() + 1
    );
}
```

**私有辅助：callLLM()**
```java
private String callLLM(String systemPrompt, String userPrompt) {
    try {
        String result = chatClient.prompt()
            .system(systemPrompt)
            .user(userPrompt)
            .call()
            .content();
        // 剥离```json```围栏
        if (result != null && result.contains("```json")) {
            int start = result.indexOf("\n", result.indexOf("```json")) + 1;
            int end = result.lastIndexOf("```");
            if (end > start) return result.substring(start, end).trim();
        }
        return result != null ? result : "{}";
    } catch (Exception e) {
        log.warn("LLM调用失败，原因：{}", e.getMessage());
        return "{}";
    }
}
```

**私有辅助：parseScore()**
```java
private int parseScore(String cioJson) {
    try {
        return objectMapper.readTree(cioJson).path("score").asInt(0);
    } catch (Exception e) {
        return 0;
    }
}
```

### 5.4 构建图：buildGraph()

```java
public CompiledGraph<DebateState> buildGraph() throws Exception {
    return StateGraph.<DebateState>builder(DebateState::new)
        .addNode("expert_debate", this::expertDebateNode)
        .addNode("cio_cross", this::cioCrossNode)
        .addNode("cio_final", this::cioFinalNode)
        .addEdge(START, "expert_debate")
        .addEdge("expert_debate", "cio_cross")
        .addEdge("cio_cross", "cio_final")
        .addConditionalEdge("cio_final", state -> {
            int score = state.getScore();
            int round = state.getRound();
            if (score >= PASS_SCORE || round > MAX_DEBATE_ROUNDS) {
                return "END";
            }
            return "expert_debate"; // 返回重试，形成环
        })
        .build();
}
```

---

## 6. CryptoAgentService.java 修改

### 6.1 修复编译错误

把现有代码里所有`.functions(...)`改为`.tools(marketTools)`：
- 注入`MarketTools marketTools`
- QUICK分支：`.tools(marketTools)`
- `gatherIntelligence()`里：`.tools(marketTools)`

### 6.2 注入DebateGraph

```java
private final DebateGraph debateGraph;
```

### 6.3 DEEP分支改造

```java
// Step1：情报员收集数据
pushMessage(emitter, sessionId, "情报员正在收集市场数据...", null);
String brief = gatherIntelligence(question, sessionId);

// Step2：构建初始State
Map<String, Object> initData = new HashMap<>();
initData.put("brief", brief);
initData.put("question", question);
initData.put("round", 1);

// Step3：运行辩论图
pushMessage(emitter, sessionId, "多位分析师正在进行研判...", null);
CompiledGraph<DebateState> graph = debateGraph.buildGraph();
DebateState finalState = graph.invoke(initData).get();

// Step4：解析CIO最终结论
String cioJson = finalState.getCioResult();
JsonNode cio = objectMapper.readTree(cioJson);

// Step5：构建最终流式输出Prompt
String finalPrompt = "用户问题：" + question + "\n\n" +
    "【多维度分析结论】\n" +
    "综合评分：" + finalState.getScore() + "/100\n" +
    cio.path("summary").asText() + "\n\n" +
    "开仓价位：" + cio.path("entryPrice").asText() + "\n" +
    "止盈目标：" + cio.path("takeProfitTargets").asText() + "\n" +
    "止损价位：" + cio.path("stopLoss").asText() + "\n" +
    "建议仓位：" + cio.path("positionSize").asText() + "\n" +
    "权重洞察：" + cio.path("weightInsight").asText() + "\n\n" +
    "请基于以上分析结论，用自然流畅的中文向用户解释，" +
    "结论先行，口语化，末尾附上JSON分析卡片。";

List<Message> messages = buildMessages(contextMessages, finalPrompt, SYSTEM_PROMPT_DEEP);
// 流式输出（原有逻辑不变）
```

---

## 7. 完整流程图
用户问题（DEEP意图）
↓
【Spring AI】情报员 gatherIntelligence()
chatClient.tools(marketTools)
LLM自主调：getPrice/getIndicators/searchNews/getPortfolio
输出：投研情报简报
↓
【LangGraph4J】DebateGraph
Node: expert_debate（三专家并行，含权重分配）
↓
Node: cio_cross（CIO提取矛盾，质询专家）
↓
Node: cio_final（CIO综合裁决，打分）
↓
条件边：score≥80或round>MAX → END
score<80且round≤MAX → expert_debate（环）
↓
【Spring AI】流式输出最终结论给用户

---

## 8. 注意事项

- `buildGraph()`每次请求调用时构建，不缓存（State是有状态的）
- 三专家并行用`ForkJoinPool.commonPool()`，无需额外配置
- 所有专家/CIO调用非流式（call()），只有最终输出流式（stream()）
- `MAX_DEBATE_ROUNDS=2`，改这一个常量控制最大轮数
- LangGraph4J的State key必须和`DebateState`的getter/setter对应
- 专家调用失败降级返回`{}`空JSON，CIO收到空报告视为"无意见"