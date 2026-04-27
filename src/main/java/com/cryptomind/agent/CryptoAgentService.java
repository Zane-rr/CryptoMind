package com.cryptomind.agent;

import com.cryptomind.dto.AnalysisCardDTO;
import com.cryptomind.dto.ChatResponse;
import com.cryptomind.entity.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 加密货币分析 Agent 主入口，内置 Intent Router：
 * CHAT（闲聊）→ 跳过Tool Calling，轻量流式回复。
 * QUICK（快问）→ chatClient.tools(marketTools)，LLM自主调工具，非流式输出。
 * DEEP（深度分析）→ 情报员收集数据（Spring AI @Tool）→ LangGraph4J辩论图裁决 → 流式输出结论。
 * 内部完成 SSE 状态提示和流式 chunk 推送，不负责消息持久化（由 ChatServiceImpl 完成）。
 * 模块：Agent
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CryptoAgentService {

    // -------------------------------------------------------------------------
    // System Prompt 常量
    // -------------------------------------------------------------------------

    /** CHAT意图：闲聊，专业友好风格 */
    private static final String SYSTEM_PROMPT_CHAT =
            "你是CryptoMind，专业的加密货币分析助手。\n" +
            "回复时直接进入正题，无需对用户使用任何称呼或寒暄语，语气专业简洁。\n" +
            "只讨论加密货币相关话题，其他话题礼貌拒绝并引导回来。\n" +
            "输出格式：不使用 ** 加粗等Markdown语法，用自然段落表达，段落间空一行。\n" +
            "用中文回答。";

    /** QUICK意图：简洁直接地回答单一问题 */
    private static final String SYSTEM_PROMPT_QUICK =
            "你是CryptoMind，专业的加密货币分析助手。\n" +
            "回答规则：\n" +
            "- 直接进入正题，无需对用户使用任何称呼或寒暄语，语气专业简洁\n" +
            "- 先给结论，再简单说明依据\n" +
            "【严格的新闻/消息面规则】\n" +
            "- 所有消息面分析、重大事件、市场动态，必须且只能基于 searchNews 工具返回的新闻内容\n" +
            "- 严禁使用训练数据中记忆的历史新闻、事件或市场消息（你的训练数据有时效截止，用它回答会产生严重幻觉）\n" +
            "- 如果 searchNews 未返回任何新闻，或返回结果与当前问题无关，必须明确说明'当前暂无相关近期新闻'，不得自行编造或从记忆补充\n" +
            "- 新闻标题必须原文照抄工具返回内容，禁止翻译、改写、意译标题，禁止替换为自己记忆中的新闻\n" +
            "- 新闻标题若为 [标题](url) 格式，必须完整保留该格式输出，不得去掉括号或链接\n" +
            "- 技术面分析（K线形态、指标解读等）可以使用专业知识\n" +
            "【输出格式规范】\n" +
            "- 章节标题使用【标题】格式，如【结论】【技术面】【消息面】【操作建议】\n" +
            "- 不使用 ** 加粗语法，不使用任何Markdown\n" +
            "- 每个章节之间空一行\n" +
            "- 新闻列举格式：[序号] 标题 (MM/dd HH:mm)\\n摘要内容\n" +
            "用中文回答。";

    /** DEEP意图：专业综合分析，结论先行，给出具体交易建议 */
    private static final String SYSTEM_PROMPT_DEEP =
            "你是CryptoMind，专业的加密货币分析助手。\n" +
            "直接进入正题，无需对用户使用任何称呼或寒暄语，语气专业简洁。\n" +
            "回答结构：【结论】→【技术面】→【消息面】→【风险提示】→【操作建议】\n" +
            "- 必须给出具体止损位、止盈目标、建议仓位比例\n" +
            "- 敢于表态，不模棱两可，观点必须基于下方提供的数据\n" +
            "- 风险提示点到为止，不要每句都免责\n" +
            "- 在回答末尾附上JSON分析卡片\n" +
            "【严格的新闻/消息面规则】\n" +
            "- 消息面分析必须且只能基于下方《投研情报简报》中的新闻内容，这些是实时抓取的数据\n" +
            "- 严禁使用训练数据中记忆的历史新闻、事件或市场消息（你的训练数据有时效截止，用它回答会产生严重幻觉）\n" +
            "- 如果简报中没有相关新闻，消息面一栏写'当前暂无相关近期新闻，消息面中性'，不得自行补充记忆中的内容\n" +
            "- 技术面分析（指标、K线形态、支撑阻力）可以充分运用专业知识\n" +
            "【输出格式规范】\n" +
            "- 每个章节标题用【标题】格式单独一行，如【结论】【技术面】\n" +
            "- 不使用 ** 加粗语法，不使用任何Markdown\n" +
            "- 章节之间空一行，段落清晰\n" +
            "- 新闻列举格式：[序号] 标题 (MM/dd HH:mm)\\n摘要内容\n" +
            "用中文回答。";

    /** DEEP意图-情报员：自主调用@Tool收集数据，输出投研情报简报 */
    private static final String SYSTEM_PROMPT_INTELLIGENCE_OFFICER =
            "你是CryptoMind的情报员，负责收集加密货币市场的实时数据。\n" +
            "可用工具：getPrice、getIndicators、searchNews、getPortfolio。\n" +
            "根据用户问题自主决定调用哪些工具，收集完成后整理成《投研情报简报》，包含：\n" +
            "- 当前价格和24h涨跌情况\n" +
            "- 关键技术指标（RSI、MACD、布林带）\n" +
            "- 相关新闻摘要（严格只转述工具返回的新闻，禁止翻译或改写标题，禁止补充任何记忆中的事件）\n" +
            "【铁律】新闻标题必须原文照抄searchNews工具的返回内容，不得翻译、意译或替换为训练记忆中的新闻。\n" +
            "格式清晰，数据准确，只陈述工具返回的事实，不做任何投资建议。\n" +
            "用中文输出。";

    /** 历史上下文最大携带条数 */
    private static final int MAX_CONTEXT_MESSAGES = 10;

    // /** 非正式开场称呼过滤模式，直接在流式首个chunk里剥离（暂时停用） */
    // private static final Pattern INFORMAL_OPENER =
    //         Pattern.compile("^(?:兄弟|朋友|老铁|亲)[，,！!、\\s]+");

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final MarketTools marketTools;
    private final DebateGraph debateGraph;

    /**
     * 执行加密货币分析流程并通过 SSE 推送结果。
     * Step1 推送状态提示 → Step2 LLM意图路由（CHAT/QUICK/DEEP）
     * → Step3 按意图分支：
     *   CHAT: buildMessages → 流式输出
     *   QUICK: tools(marketTools)非流式 → 推送全量回复
     *   DEEP: gatherIntelligence → DebateGraph.invoke() → buildFinalPrompt → 流式输出
     * → Step4 返回AgentResult。
     *
     * @param question        用户问题
     * @param symbol          前端传入的币种（可为null，自动识别）
     * @param contextMessages 历史上下文消息列表
     * @param emitter         SSE发射器
     * @param sessionId       会话ID
     * @param objectMapper    JSON序列化器（与字段同实例，参数保持签名兼容）
     * @return AgentResult 包含完整回复和分析卡片（非DEEP时卡片为null）
     * @throws Exception DeepSeek流式调用或辩论图执行失败时抛出
     */
    public AgentResult analyzeStream(
            String question,
            String symbol,
            List<ChatMessage> contextMessages,
            SseEmitter emitter,
            String sessionId,
            ObjectMapper objectMapper) throws Exception {

        // Step1: 立刻推送心跳，让前端知道请求已受理
        pushHeartbeat(emitter, "意图识别中...");

        // Step2: 单次LLM调用提取意图类型、币种和英文搜索关键词
        IntentResult intent = extractIntentByLLM(question);
        String resolvedSymbol = resolveSymbol(symbol, intent.symbol);
        log.info("意图识别结果：intent={}，symbol={}，keywords={}，sessionId={}",
                intent.intent, resolvedSymbol, intent.searchKeywords, sessionId);

        // Step3: 按意图路由
        List<Message> messages;
        StringBuilder fullContent = new StringBuilder();

        if ("CHAT".equals(intent.intent)) {
            // CHAT分支：跳过Tool Calling，直接构建消息流式输出
            pushHeartbeat(emitter, "正在思考...");
            messages = buildMessages(contextMessages, question, SYSTEM_PROMPT_CHAT);

        } else if ("QUICK".equals(intent.intent)) {
            // QUICK分支：新闻类问题主动预取数据注入Prompt，其余直接流式回答
            pushHeartbeat(emitter, "正在查询相关数据...");
            String userContent = question;
            if (intent.needsNews) {
                pushHeartbeat(emitter, "正在获取实时新闻...");
                String newsData = marketTools.searchAndCompressNews(intent.searchKeywords);
                log.info("QUICK新闻预取完成，keywords={}，数据长度={}", intent.searchKeywords, newsData.length());
                userContent = "【实时新闻数据（以下内容来自实时搜索，你必须且只能基于这些新闻回答，禁止使用训练数据中的新闻）】\n" +
                        newsData + "\n\n用户问题：" + question;
                pushHeartbeat(emitter, "实时新闻获取完毕，正在分析...");
            }
            messages = buildMessages(contextMessages, userContent, SYSTEM_PROMPT_QUICK);
            try {
                chatClient.prompt()
                        .messages(messages)
                        .stream()
                        .content()
                        .doOnNext(chunk -> {
                            fullContent.append(chunk);
                            try {
                                pushContent(emitter, sessionId, chunk);
                            } catch (IOException e) {
                                throw new RuntimeException("SSE推送失败", e);
                            }
                        })
                        .blockLast();
            } catch (Exception e) {
                log.error("QUICK分支流式调用失败，sessionId={}，原因：{}", sessionId, e.getMessage(), e);
                try { pushContent(emitter, sessionId, "数据获取失败，请稍后重试"); } catch (IOException ignored) {}
            }
            return new AgentResult(fullContent.toString(), null);

        } else {
            // DEEP分支：情报员收集数据 → LangGraph4J辩论图 → 构建最终Prompt

            // Step3a: 情报员用@Tool收集市场数据，生成投研情报简报
            pushHeartbeat(emitter, "[情报员] 正在调用工具收集市场实时数据...");
            String brief = gatherIntelligence(question, sessionId);
            pushHeartbeat(emitter, "[情报员] 数据收集完毕，简报已生成");

            // Step3b: 构建辩论图初始State
            Map<String, Object> initData = new HashMap<>();
            initData.put("brief",    brief);
            initData.put("question", question);
            initData.put("round",    1);

            // Step3c: 运行LangGraph4J辩论图，通过heartbeat Consumer实时推送节点日志
            Consumer<String> heartbeat = msg -> pushHeartbeat(emitter, msg);
            CompiledGraph<DebateState> graph = debateGraph.buildGraph(heartbeat);
            DebateState finalState = graph.invoke(initData).get();

            // Step3d: 解析CIO最终裁决，构建流式输出Prompt
            String cioJson = finalState.getCioResult();
            JsonNode cio   = objectMapper.readTree(cioJson.isBlank() ? "{}" : cioJson);

            String finalPrompt = "用户问题：" + question + "\n\n" +
                    "【多维度分析结论】\n" +
                    "综合评分：" + finalState.getScore() + "/100\n" +
                    cio.path("summary").asText("综合分析暂时不可用") + "\n\n" +
                    "开仓价位：" + cio.path("entryPrice").asText("-") + "\n" +
                    "止盈目标：" + cio.path("takeProfitTargets").asText("-") + "\n" +
                    "止损价位：" + cio.path("stopLoss").asText("-") + "\n" +
                    "建议仓位：" + cio.path("positionSize").asText("-") + "\n" +
                    "权重洞察：" + cio.path("weightInsight").asText("-") + "\n\n" +
                    "请基于以上分析结论，用自然流畅的中文向用户解释，结论先行，口语化表达，在末尾附上如下格式的JSON分析卡片：\n" +
                    "```json\n" +
                    "{\n" +
                    "  \"symbol\": \"" + (resolvedSymbol != null ? resolvedSymbol : "UNKNOWN") + "\",\n" +
                    "  \"suggestion\": \"买入/卖出/观望\",\n" +
                    "  \"confidence\": 0到100整数,\n" +
                    "  \"technicalSummary\": \"技术面分析摘要\",\n" +
                    "  \"newsSummary\": \"新闻面分析摘要\",\n" +
                    "  \"riskAdvice\": \"风险提示\",\n" +
                    "  \"entryPrice\": \"具体价位\",\n" +
                    "  \"takeProfitTargets\": \"第一目标:xxx，第二目标:xxx\",\n" +
                    "  \"stopLoss\": \"止损价位:xxx\",\n" +
                    "  \"positionSize\": \"建议仓位xx%-xx%\"\n" +
                    "}\n" +
                    "```";

            messages = buildMessages(contextMessages, finalPrompt, SYSTEM_PROMPT_DEEP);
        }

        // Step4: 流式调用（CHAT 和 DEEP 共用）
        try {
            chatClient.prompt()
                    .messages(messages)
                    .stream()
                    .content()
                    .doOnNext(chunk -> {
                        fullContent.append(chunk);
                        try {
                            pushContent(emitter, sessionId, chunk);
                        } catch (IOException e) {
                            throw new RuntimeException("SSE chunk推送失败", e);
                        }
                    })
                    .blockLast();
        } catch (Exception e) {
            log.error("DeepSeek流式调用失败，sessionId={}，原因：{}", sessionId, e.getMessage(), e);
            try { pushContent(emitter, sessionId, "AI分析失败，请稍后重试"); } catch (IOException ignored) {}
            emitter.completeWithError(e);
            throw e;
        }

        // Step5: 仅DEEP意图解析分析卡片
        AnalysisCardDTO card = "DEEP".equals(intent.intent) ? tryParseCard(fullContent.toString()) : null;
        return new AgentResult(fullContent.toString(), card);
    }

    // -------------------------------------------------------------------------
    // 意图路由与币种标准化
    // -------------------------------------------------------------------------

    /**
     * 单次调用DeepSeek提取意图类型、币种和英文搜索关键词。
     * 解析失败降级：intent=DEEP，symbol=null，searchKeywords=原始question。
     *
     * @param question 用户问题文本
     * @return IntentResult，包含路由所需的全部字段
     */
    private IntentResult extractIntentByLLM(String question) {
        try {
            String prompt = "分析用户问题，返回以下JSON，不要返回任何其他内容：\n" +
                    "{\n" +
                    "  \"intent\": \"CHAT或QUICK或DEEP\",\n" +
                    "  \"symbol\": \"交易对如BTCUSDT，识别不到填null\",\n" +
                    "  \"searchKeywords\": \"用于搜索新闻的英文关键词3-5个词\",\n" +
                    "  \"needsNews\": true或false\n" +
                    "}\n\n" +
                    "意图判断规则：\n" +
                    "- CHAT：闲聊、问候、与加密货币完全无关的问题\n" +
                    "- QUICK：只问单一信息，如价格、某个指标、最近新闻动态\n" +
                    "- DEEP：需要综合判断的问题，如能买吗、能抄底吗、怎么操作、风控建议、止损怎么设\n" +
                    "needsNews判断规则：\n" +
                    "- true：问题涉及新闻、消息面、市场动态、最新事件、重大事件、某币种近期消息\n" +
                    "- false：只问价格、技术指标、持仓等不涉及新闻的问题\n\n" +
                    "用户问题：" + question;
            String result = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            if (result == null) {
                return IntentResult.fallback(question);
            }
            // 兼容LLM在JSON外包裹markdown代码块的情况
            String json = result.trim();
            if (json.startsWith("```")) {
                int start = json.indexOf("\n") + 1;
                int end   = json.lastIndexOf("```");
                if (end > start) {
                    json = json.substring(start, end).trim();
                }
            }
            var node = objectMapper.readTree(json);
            String llmIntent   = node.path("intent").asText("DEEP").toUpperCase();
            String llmSymbol   = node.path("symbol").asText(null);
            String llmKeywords = node.path("searchKeywords").asText(null);
            boolean llmNeedsNews = node.path("needsNews").asBoolean(false);

            // 校验intent合法性，非法值降级为DEEP
            if (!List.of("CHAT", "QUICK", "DEEP").contains(llmIntent)) {
                llmIntent = "DEEP";
            }
            // "null"字符串视为未设置
            if ("null".equalsIgnoreCase(llmSymbol) || llmSymbol == null || llmSymbol.isBlank()) {
                llmSymbol = null;
            }
            if (llmKeywords == null || llmKeywords.isBlank()) {
                llmKeywords = question;
            }
            return new IntentResult(llmIntent, llmSymbol, llmKeywords, llmNeedsNews);
        } catch (Exception e) {
            log.warn("LLM意图识别失败，降级为DEEP，原因：{}", e.getMessage());
            return IntentResult.fallback(question);
        }
    }

    /**
     * 标准化币种代码：LLM识别结果优先，兜底使用前端传入的symbol参数。
     *
     * @param frontendSymbol 前端传入的币种参数（可为null）
     * @param llmSymbol      extractIntentByLLM识别出的币种（可为null）
     * @return 标准化后的交易对，如BTCUSDT；无法识别时返回null
     */
    private String resolveSymbol(String frontendSymbol, String llmSymbol) {
        if (llmSymbol != null) {
            String upper = llmSymbol.trim().toUpperCase();
            return upper.endsWith("USDT") ? upper : upper + "USDT";
        }
        if (frontendSymbol != null && !frontendSymbol.isBlank()) {
            String upper = frontendSymbol.trim().toUpperCase();
            return upper.endsWith("USDT") ? upper : upper + "USDT";
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // DEEP分支：情报员数据收集（Spring AI @Tool）
    // -------------------------------------------------------------------------

    /**
     * 情报员角色：带 @Tool 工具权限调用DeepSeek，LLM自主调用工具收集数据，
     * 返回《投研情报简报》字符串供 DebateGraph 使用。
     * 失败降级返回提示字符串，不抛异常不中断流程。
     *
     * @param question  用户问题
     * @param sessionId 会话ID，用于日志追踪
     * @return 投研情报简报字符串，或降级提示
     */
    private String gatherIntelligence(String question, String sessionId) {
        try {
            String brief = chatClient.prompt()
                    .system(SYSTEM_PROMPT_INTELLIGENCE_OFFICER)
                    .user("用户问题：" + question + "\n请收集相关数据并生成投研情报简报。")
                    .tools(marketTools)
                    .call()
                    .content();
            return brief != null ? brief : "情报收集失败，请直接基于问题进行分析";
        } catch (Exception e) {
            log.error("情报员收集数据失败，sessionId={}，原因：{}", sessionId, e.getMessage(), e);
            return "情报收集失败，请直接基于问题进行分析";
        }
    }

    // -------------------------------------------------------------------------
    // 消息列表构建
    // -------------------------------------------------------------------------

    /**
     * 按 System → 历史上下文（最近10条）→ 当前UserPrompt 顺序构建消息列表。
     * ChatMessage.role 为 "user" 映射 UserMessage，"assistant" 映射 AssistantMessage。
     *
     * @param contextMessages 历史消息列表
     * @param userPrompt      当前轮次的用户Prompt
     * @param systemPrompt    按意图选择的SystemPrompt常量
     * @return Spring AI Message列表
     */
    private List<Message> buildMessages(List<ChatMessage> contextMessages,
                                        String userPrompt,
                                        String systemPrompt) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));

        // 取最近N条历史上下文，过多会超出Token限制
        int start = Math.max(0, contextMessages.size() - MAX_CONTEXT_MESSAGES);
        for (ChatMessage msg : contextMessages.subList(start, contextMessages.size())) {
            if ("user".equals(msg.getRole())) {
                messages.add(new UserMessage(msg.getContent()));
            } else if ("assistant".equals(msg.getRole())) {
                messages.add(new AssistantMessage(msg.getContent()));
            }
        }

        messages.add(new UserMessage(userPrompt));
        return messages;
    }

    // -------------------------------------------------------------------------
    // 分析卡片解析（仅DEEP意图调用）
    // -------------------------------------------------------------------------

    /**
     * 从完整回复中提取 ```json ... ``` 代码块并反序列化为 AnalysisCardDTO。
     * 解析失败返回 null，不抛异常，不影响文字回复的正常返回。
     * 注意：仅在DEEP意图时由 analyzeStream() 调用，CHAT/QUICK直接跳过。
     *
     * @param content DeepSeek完整输出文本
     * @return 解析成功的 AnalysisCardDTO，或 null
     */
    private AnalysisCardDTO tryParseCard(String content) {
        try {
            int jsonStart = content.indexOf("```json");
            if (jsonStart == -1) {
                return null;
            }
            int codeStart = content.indexOf("\n", jsonStart) + 1;
            int codeEnd   = content.indexOf("```", codeStart);
            if (codeEnd == -1) {
                return null;
            }
            String json = content.substring(codeStart, codeEnd).trim();
            return objectMapper.readValue(json, AnalysisCardDTO.class);
        } catch (Exception e) {
            log.warn("分析卡片解析失败，原因：{}", e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // SSE推送
    // -------------------------------------------------------------------------

    /**
     * 推送 HEARTBEAT 类型心跳日志，前端显示在日志控制台。
     * 内部捕获所有异常，不影响主流程。
     *
     * @param emitter SSE发射器
     * @param content 日志内容
     */
    private void pushHeartbeat(SseEmitter emitter, String content) {
        try {
            ChatResponse r = new ChatResponse();
            r.setType("HEARTBEAT");
            r.setContent(content);
            emitter.send(SseEmitter.event()
                    .name("message")
                    .data(objectMapper.writeValueAsString(r)));
        } catch (Exception ignored) {}
    }

    /**
     * 推送 CONTENT 类型流式文字 chunk，前端追加到对话气泡。
     *
     * @param emitter   SSE发射器
     * @param sessionId 会话ID
     * @param content   文字片段
     * @throws IOException SSE发送失败时抛出
     */
    private void pushContent(SseEmitter emitter, String sessionId, String content) throws IOException {
        ChatResponse r = new ChatResponse();
        r.setType("CONTENT");
        r.setSessionId(sessionId);
        r.setRole("assistant");
        r.setContent(content);
        emitter.send(SseEmitter.event()
                .name("message")
                .data(objectMapper.writeValueAsString(r)));
    }

    // -------------------------------------------------------------------------
    // 内部数据类
    // -------------------------------------------------------------------------

    /**
     * extractIntentByLLM() 的返回值，封装意图路由所需的全部字段。
     */
    private static class IntentResult {
        /** 意图类型：CHAT / QUICK / DEEP */
        final String intent;
        /** 识别出的交易对符号，如BTCUSDT；未识别到为null */
        final String symbol;
        /** 用于ES语义检索的英文关键词，识别失败时降级为原始用户问题 */
        final String searchKeywords;
        /** 是否需要实时新闻数据：问题涉及新闻/消息面时为true */
        final boolean needsNews;

        IntentResult(String intent, String symbol, String searchKeywords, boolean needsNews) {
            this.intent         = intent;
            this.symbol         = symbol;
            this.searchKeywords = searchKeywords;
            this.needsNews      = needsNews;
        }

        /** 降级兜底：DEEP意图，无symbol，关键词=原始问题，不预取新闻 */
        static IntentResult fallback(String question) {
            return new IntentResult("DEEP", null, question, false);
        }
    }
}
