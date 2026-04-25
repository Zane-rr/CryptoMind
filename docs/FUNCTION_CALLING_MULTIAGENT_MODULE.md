# Function Calling + Multi-Agent模块开发文档

## 1. 改造目标

在现有 CryptoAgentService 基础上实现两件事：
1. 引入 Spring AI Function Calling，让LLM自主决定调哪些工具收集数据
2. DEEP意图引入多角色并行辩论机制（情报员→三专家→CIO裁决）

---

## 2. 改造文件清单

新增文件：
- `agent/MarketTools.java` — 注册4个Function Bean

修改文件：
- `agent/CryptoAgentService.java` — 主要改造
- `config/ChatClientConfig.java` — 无需修改

---

## 3. MarketTools.java

路径：`agent/MarketTools.java`

注解：`@Configuration` `@RequiredArgsConstructor` `@Slf4j`

注入：`IMarketService` `INewsService` `ObjectMapper`

注册4个Function Bean，每个Bean用`@Bean` + `@Description`：

### getPrice
```java
@Bean
@Description("获取加密货币实时价格和24小时涨跌幅，输入交易对如BTCUSDT")
public Function getPrice() {
    return request -> {
        try {
            return objectMapper.writeValueAsString(
                marketService.getPrice(request.symbol()));
        } catch (Exception e) {
            log.warn("getPrice失败，symbol={}，原因：{}", request.symbol(), e.getMessage());
            return "价格数据获取失败";
        }
    };
}

record GetPriceRequest(String symbol) {}
```

### getIndicators
```java
@Bean
@Description("获取加密货币技术指标，包含RSI、MACD、布林带，输入交易对如BTCUSDT")
public Function getIndicators() {
    return request -> {
        try {
            return objectMapper.writeValueAsString(
                marketService.getIndicators(request.symbol(), "1h"));
        } catch (Exception e) {
            log.warn("getIndicators失败，symbol={}，原因：{}", request.symbol(), e.getMessage());
            return "技术指标获取失败";
        }
    };
}

record GetIndicatorsRequest(String symbol) {}
```

### searchNews
```java
@Bean
@Description("搜索加密货币相关新闻，输入英文关键词效果最佳，如Bitcoin BTC crypto")
public Function searchNews() {
    return request -> {
        try {
            NewsSearchRequest req = new NewsSearchRequest();
            req.setQuery(request.keywords());
            req.setSymbol(null);
            req.setLimit(5);
            NewsSearchResponse resp = newsService.searchNews(req);
            if (resp.getNews() == null || resp.getNews().isEmpty()) {
                return "暂无相关新闻";
            }
            StringBuilder sb = new StringBuilder();
            for (NewsDTO news : resp.getNews()) {
                sb.append("- ").append(news.getTitle()).append("\n");
                if (news.getSummary() != null && !news.getSummary().isBlank()) {
                    sb.append("  ").append(news.getSummary()).append("\n");
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("searchNews失败，原因：{}", e.getMessage());
            return "新闻数据获取失败";
        }
    };
}

record SearchNewsRequest(String keywords) {}
```

### getPortfolio
```java
@Bean
@Description("获取币安账户当前持仓情况，无需输入参数")
public Function getPortfolio() {
    return request -> {
        try {
            return objectMapper.writeValueAsString(
                marketService.getPortfolio());
        } catch (Exception e) {
            log.warn("getPortfolio失败，原因：{}", e.getMessage());
            return "持仓数据获取失败";
        }
    };
}

record GetPortfolioRequest() {}
```

---

## 4. CryptoAgentService.java 改造

### 4.1 新增注入

无需新增，`ChatClient`已注入。

### 4.2 新增System Prompt常量

#### SYSTEM_PROMPT_INTELLIGENCE_OFFICER
你是CryptoMind的情报员，负责收集加密货币市场数据。
你有以下工具可以使用：getPrice、getIndicators、searchNews、getPortfolio。
根据用户问题，自主决定需要调用哪些工具收集数据。
收集完成后，整理成一份简洁的《投研情报简报》，包含：

当前价格和涨跌情况
关键技术指标（RSI、MACD、布林带）
相关新闻摘要
格式清晰，数据准确，不做任何投资建议，只陈述事实。
用中文输出。


#### SYSTEM_PROMPT_BULLISH
你是一位激进的加密货币多头分析师。
你的任务：基于情报简报，只寻找看涨信号和做多理由。
只返回如下JSON，不要任何其他内容：
{
"role": "BULLISH",
"verdict": "做多/观望",
"confidence": 0到100整数,
"keySignals": ["信号1", "信号2", "信号3"],
"entryPrice": "建议开仓价位",
"takeProfitTargets": "第一目标:xxx，第二目标:xxx",
"reasoning": "核心做多逻辑，2-3句话"
}

#### SYSTEM_PROMPT_RISK
你是一位极度谨慎的风险控制师。
你的任务：基于情报简报，只寻找风险信号和爆仓风险。
只返回如下JSON，不要任何其他内容：
{
"role": "RISK",
"verdict": "高风险/中风险/低风险",
"confidence": 0到100整数,
"keyRisks": ["风险1", "风险2", "风险3"],
"stopLoss": "止损价位:xxx，跌破离场",
"positionSize": "建议仓位占总资金的xx%-xx%",
"reasoning": "核心风险逻辑，2-3句话"
}

#### SYSTEM_PROMPT_QUANT
你是一位只看数字的量化策略师。
你的任务：基于情报简报，纯数字分析，计算具体交易参数。
只返回如下JSON，不要任何其他内容：
{
"role": "QUANT",
"verdict": "执行/等待",
"confidence": 0到100整数,
"riskRewardRatio": "盈亏比如1:2.5",
"entryPrice": "精确开仓价",
"stopLoss": "精确止损价",
"takeProfitTargets": "第一目标:xxx，第二目标:xxx",
"reasoning": "数字依据，2-3句话"
}

#### SYSTEM_PROMPT_CIO
你是首席投资官（CIO），负责综合评审三位分析师报告并做出最终裁决。
只返回如下JSON，不要任何其他内容：
{
"score": 0到100整数,
"finalVerdict": "买入/卖出/观望",
"bestRole": "BULLISH或RISK或QUANT",
"summary": "综合三份报告的最终建议，结论先行，3-5句话",
"entryPrice": "综合后建议开仓价",
"stopLoss": "综合后止损价",
"takeProfitTargets": "综合后止盈目标",
"positionSize": "综合后建议仓位"
}

### 4.3 新增内部类

#### ExpertReport（三个专家的报告）
```java
private static class ExpertReport {
    String role;         // BULLISH / RISK / QUANT
    String verdict;
    int confidence;
    String reasoning;
    String rawJson;      // 原始JSON，给CIO用
}
```

#### CIOResult（CIO裁决结果）
```java
private static class CIOResult {
    int score;
    String finalVerdict;
    String bestRole;
    String summary;
    String entryPrice;
    String stopLoss;
    String takeProfitTargets;
    String positionSize;
}
```

### 4.4 新增方法：gatherIntelligence()
private String gatherIntelligence(String question, String sessionId)

职责：情报员角色，带Tool权限调用DeepSeek，自主收集数据，返回投研情报简报字符串。

实现：
```java
// 用chatClient带functions调用，非流式
String brief = chatClient.prompt()
    .system(SYSTEM_PROMPT_INTELLIGENCE_OFFICER)
    .user("用户问题：" + question + "\n请收集相关数据并生成投研情报简报。")
    .functions("getPrice", "getIndicators", "searchNews", "getPortfolio")
    .call()
    .content();
return brief != null ? brief : "情报收集失败";
```

失败降级：返回"情报收集失败，请直接基于问题进行分析"。

### 4.5 新增方法：callExpert()
private ExpertReport callExpert(String systemPrompt, String brief, String role)

职责：调用单个专家角色分析情报简报，返回ExpertReport。

实现：
- 非流式调用DeepSeek
- SystemPrompt=对应专家Prompt
- UserPrompt="以下是投研情报简报，请进行分析：\n{brief}"
- 解析返回JSON填充ExpertReport
- 解析失败：confidence=0，reasoning="分析失败"，rawJson="{}"

### 4.6 新增方法：callCIO()
private CIOResult callCIO(ExpertReport bullish, ExpertReport risk, ExpertReport quant)

职责：CIO汇总三份报告裁决，返回CIOResult。

UserPrompt：
以下是三位分析师的报告，请综合评审并给出最终裁决：
【激进分析师报告】
{bullish.rawJson}
【风控师报告】
{risk.rawJson}
【量化策略师报告】
{quant.rawJson}

解析失败降级：score=0，bestRole取三者中confidence最高的role。

### 4.7 新增方法：runMultiAgentDebate()
private CIOResult runMultiAgentDebate(String brief, String question)

流程：

**第一轮：**
并行启动三个CompletableFuture：

bullishFuture = CompletableFuture.supplyAsync(() -> callExpert(BULLISH, brief, "BULLISH"))
riskFuture    = CompletableFuture.supplyAsync(() -> callExpert(RISK, brief, "RISK"))
quantFuture   = CompletableFuture.supplyAsync(() -> callExpert(QUANT, brief, "QUANT"))

CompletableFuture.allOf(bullishFuture, riskFuture, quantFuture).get(30, TimeUnit.SECONDS)
CIOResult cio = callCIO(bullish, risk, quant)

score≥80 → 直接返回cio

**第二轮（score<80时）：**
找出confidence最高的ExpertReport → bestReport
另外两个角色的brief追加：
"请重新审视以下{bestReport.role}的分析，结合你的视角重新评估：\n{bestReport.rawJson}"
重新并行调用另外两个角色（bestReport不变）
再次callCIO()

**保底（两轮都<80）：**
取三份报告中confidence最高的ExpertReport
构造CIOResult：

score = bestReport.confidence
finalVerdict = bestReport.verdict
bestRole = bestReport.role
summary = "当前市场信号存在分歧，以下基于" + bestRole + "视角给出建议，请谨慎操作。\n" + bestReport.reasoning
其他字段从bestReport取


返回该CIOResult。

### 4.8 修改 analyzeStream() 的DEEP分支

原来：
```java
marketData = fetchMarketData(resolvedSymbol);
newsData = fetchNews(intent.searchKeywords);
// buildPrompt → buildMessages → 流式输出
```

改为：
```java
// Step1：情报员收集数据
pushMessage(emitter, sessionId, "情报员正在收集市场数据...", null);
String brief = gatherIntelligence(question, sessionId);

// Step2：三专家并行辩论
pushMessage(emitter, sessionId, "多位分析师正在进行研判...", null);
CIOResult cio = runMultiAgentDebate(brief, question);

// Step3：构建最终Prompt流式输出
String finalPrompt = "用户问题：" + question + "\n\n" +
    "【多维度分析结论】\n" +
    "综合评分：" + cio.score + "/100\n" +
    cio.summary + "\n\n" +
    "开仓价位：" + cio.entryPrice + "\n" +
    "止盈目标：" + cio.takeProfitTargets + "\n" +
    "止损价位：" + cio.stopLoss + "\n" +
    "建议仓位：" + cio.positionSize + "\n\n" +
    "请基于以上分析结论，用自然流畅的中文向用户解释，" +
    "结论先行，口语化表达，在末尾附上JSON分析卡片。";

List<Message> messages = buildMessages(contextMessages, finalPrompt, SYSTEM_PROMPT_DEEP);
// 流式输出（不变）
```

### 4.9 删除的内容

- `fetchMarketData()`方法
- `fetchNews()`方法
- `IntentResult`里的`dataNeeded`字段
- `extractIntentByLLM()`里dataNeeded相关逻辑
- QUICK分支里的硬编码数据拉取（改为Tool Calling）
- `buildPrompt()`方法（DEEP分支不再需要，CHAT/QUICK简化）

### 4.10 QUICK分支改造

原来硬编码按dataNeeded拉数据，改为：
```java
// QUICK：带Tool权限，LLM自己决定调什么
String quickResponse = chatClient.prompt()
    .system(SYSTEM_PROMPT_QUICK)
    .user(question)
    .functions("getPrice", "getIndicators", "searchNews", "getPortfolio")
    .messages(buildMessages(contextMessages, question, SYSTEM_PROMPT_QUICK))
    .call()
    .content();
// 直接流式推送quickResponse
```

---

## 5. IntentResult简化

去掉dataNeeded字段，只保留：
```json
{
  "intent": "CHAT或QUICK或DEEP",
  "symbol": "BTCUSDT或null",
  "searchKeywords": "英文关键词"
}
```

extractIntentByLLM()的Prompt对应更新，去掉dataNeeded相关描述。

---

## 6. 完整流程图
用户问题
↓
extractIntentByLLM()（第一次LLM调用）
↓
CHAT → 直接流式回答，跳过Tool Calling
↓
QUICK → ChatClient带Tool权限，LLM自主调工具，流式输出
↓
DEEP：
情报员gatherIntelligence()（第二次LLM调用，带Tool权限）
→ LLM自主调getPrice/getIndicators/searchNews/getPortfolio
→ 输出投研情报简报
↓
并行三专家（第三/四/五次LLM调用，CompletableFuture）
→ Bullish / Risk / Quant各自分析简报
↓
CIO裁决（第六次LLM调用）
→ score≥80 → 输出
→ score<80 → 第二轮重试
→ 两轮不达标 → 取最高分角色兜底
↓
流式输出最终结论（第七次LLM调用）

---

## 7. 注意事项

- 情报员和三专家调用全部非流式（call()），只有最终输出流式（stream()）
- CompletableFuture使用`ForkJoinPool.commonPool()`，无需额外配置线程池
- 所有专家调用失败降级：confidence=0，不中断流程
- DEEP分支总LLM调用次数：1(意图)+1(情报员)+3(专家)+1(CIO)+1(最终输出)=7次
- 若第二轮重试，最多9次LLM调用
- functions()注册的Bean名称必须和`@Bean`方法名一致
