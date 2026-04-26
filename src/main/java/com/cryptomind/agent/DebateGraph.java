package com.cryptomind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

/**
 * LangGraph4J辩论图定义，实现"三专家并行辩论 → CIO质询 → CIO裁决"的有向有环图。
 * 每次请求调用 buildGraph() 构建新图（不缓存，因State是有状态的）。
 * 图结构：START → expert_debate → cio_cross → cio_final → (score≥80或超轮次 → END，否则 → expert_debate)
 * 模块：Agent
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DebateGraph {

    /** 最大辩论轮次，修改此常量即可控制最多重试次数 */
    private static final int MAX_DEBATE_ROUNDS = 3;

    /** CIO裁决通过分值，score≥PASS_SCORE时直接输出，不再重试 */
    private static final int PASS_SCORE = 80;

    // -------------------------------------------------------------------------
    // System Prompt 常量（专家角色 + CIO两种模式）
    // -------------------------------------------------------------------------

    /** 多头分析师：寻找看涨信号，含5维度权重分配 */
    private static final String SYSTEM_PROMPT_BULLISH =
            "你是一位激进的加密货币多头分析师。\n" +
            "基于情报简报，只寻找看涨信号和做多理由。\n" +
            "同时对以下5个维度按重要性分配权重（总和=100）：\n" +
            "价格趋势、RSI、MACD、新闻情绪、成交量\n" +
            "只返回如下JSON，不要任何其他内容：\n" +
            "{\n" +
            "\"role\": \"BULLISH\",\n" +
            "\"verdict\": \"做多/观望\",\n" +
            "\"confidence\": 0到100整数,\n" +
            "\"keySignals\": [\"信号1\", \"信号2\", \"信号3\"],\n" +
            "\"entryPrice\": \"建议开仓价位\",\n" +
            "\"takeProfitTargets\": \"第一目标:xxx，第二目标:xxx\",\n" +
            "\"reasoning\": \"核心做多逻辑，2-3句话\",\n" +
            "\"weights\": {\n" +
            "  \"priceTrend\": 权重整数,\n" +
            "  \"rsi\": 权重整数,\n" +
            "  \"macd\": 权重整数,\n" +
            "  \"newsSentiment\": 权重整数,\n" +
            "  \"volume\": 权重整数\n" +
            "}\n" +
            "}";

    /** 风控师：寻找风险信号，含5维度权重分配 */
    private static final String SYSTEM_PROMPT_RISK =
            "你是一位极度谨慎的风险控制师。\n" +
            "基于情报简报，只寻找风险信号和爆仓风险。\n" +
            "同时对以下5个维度按重要性分配权重（总和=100）：\n" +
            "价格趋势、RSI、MACD、新闻情绪、成交量\n" +
            "只返回如下JSON，不要任何其他内容：\n" +
            "{\n" +
            "\"role\": \"RISK\",\n" +
            "\"verdict\": \"高风险/中风险/低风险\",\n" +
            "\"confidence\": 0到100整数,\n" +
            "\"keyRisks\": [\"风险1\", \"风险2\", \"风险3\"],\n" +
            "\"stopLoss\": \"止损价位:xxx，跌破离场\",\n" +
            "\"positionSize\": \"建议仓位占总资金的xx%-xx%\",\n" +
            "\"reasoning\": \"核心风险逻辑，2-3句话\",\n" +
            "\"weights\": {\n" +
            "  \"priceTrend\": 权重整数,\n" +
            "  \"rsi\": 权重整数,\n" +
            "  \"macd\": 权重整数,\n" +
            "  \"newsSentiment\": 权重整数,\n" +
            "  \"volume\": 权重整数\n" +
            "}\n" +
            "}";

    /** 量化策略师：纯数字分析，含5维度权重分配 */
    private static final String SYSTEM_PROMPT_QUANT =
            "你是一位只看数字的量化策略师。\n" +
            "基于情报简报，纯数字分析，计算具体交易参数。\n" +
            "同时对以下5个维度按重要性分配权重（总和=100）：\n" +
            "价格趋势、RSI、MACD、新闻情绪、成交量\n" +
            "只返回如下JSON，不要任何其他内容：\n" +
            "{\n" +
            "\"role\": \"QUANT\",\n" +
            "\"verdict\": \"执行/等待\",\n" +
            "\"confidence\": 0到100整数,\n" +
            "\"riskRewardRatio\": \"盈亏比如1:2.5\",\n" +
            "\"entryPrice\": \"精确开仓价\",\n" +
            "\"stopLoss\": \"精确止损价\",\n" +
            "\"takeProfitTargets\": \"第一目标:xxx，第二目标:xxx\",\n" +
            "\"reasoning\": \"数字依据，2-3句话\",\n" +
            "\"weights\": {\n" +
            "  \"priceTrend\": 权重整数,\n" +
            "  \"rsi\": 权重整数,\n" +
            "  \"macd\": 权重整数,\n" +
            "  \"newsSentiment\": 权重整数,\n" +
            "  \"volume\": 权重整数\n" +
            "}\n" +
            "}";

    /** CIO质询模式：提取三份报告的核心矛盾，生成定向质询 */
    private static final String SYSTEM_PROMPT_CIO_CROSS =
            "你是首席投资官（CIO），负责主持专家辩论。\n" +
            "阅读三位分析师的报告，提取最核心的矛盾点。\n" +
            "把矛盾点直接质询相关专家，要求他们正面回应。\n" +
            "只返回如下JSON，不要任何其他内容：\n" +
            "{\n" +
            "\"contradictions\": [\n" +
            "  {\n" +
            "    \"targetRole\": \"BULLISH或RISK或QUANT\",\n" +
            "    \"question\": \"质询问题，直接犀利，要求对方解释矛盾\"\n" +
            "  }\n" +
            "],\n" +
            "\"weightConflict\": \"三人权重分配差异最大的维度是什么，一句话描述\"\n" +
            "}";

    /** CIO最终裁决模式：综合三份报告 + 质询回应做出最终裁决 */
    private static final String SYSTEM_PROMPT_CIO_FINAL =
            "你是首席投资官（CIO），基于三位分析师的报告和质询回应做出最终裁决。\n" +
            "综合考虑：各方观点、质询回应、指标权重分配差异。\n" +
            "只返回如下JSON，不要任何其他内容：\n" +
            "{\n" +
            "\"score\": 0到100整数（综合置信度，≥80建议执行）,\n" +
            "\"finalVerdict\": \"买入/卖出/观望\",\n" +
            "\"bestRole\": \"BULLISH或RISK或QUANT（最有说服力的角色）\",\n" +
            "\"summary\": \"综合结论，结论先行，3-5句话\",\n" +
            "\"entryPrice\": \"综合后建议开仓价\",\n" +
            "\"stopLoss\": \"综合后止损价\",\n" +
            "\"takeProfitTargets\": \"综合后止盈目标\",\n" +
            "\"positionSize\": \"综合后建议仓位\",\n" +
            "\"weightInsight\": \"三人权重分配的关键差异及其含义，一句话\"\n" +
            "}";

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // 图构建入口（每次请求调用，不缓存）
    // -------------------------------------------------------------------------

    /**
     * 构建并编译辩论有向有环图。每次请求调用此方法创建新图，不复用（State有状态）。
     * 图结构：expert_debate → cio_cross → cio_final →(条件边)→ END 或 expert_debate
     *
     * @param heartbeat 心跳日志回调，每个节点执行时调用，推送实时进度到前端
     * @return 编译后的 CompiledGraph，可直接 invoke()
     * @throws Exception LangGraph4J构建失败时抛出
     */
    public CompiledGraph<DebateState> buildGraph(Consumer<String> heartbeat) throws Exception {
        return new StateGraph<>(DebateState::new)
                .addNode("expert_debate", AsyncNodeAction.node_async(s -> expertDebateNode(s, heartbeat)))
                .addNode("cio_cross",     AsyncNodeAction.node_async(s -> cioCrossNode(s, heartbeat)))
                .addNode("cio_final",     AsyncNodeAction.node_async(s -> cioFinalNode(s, heartbeat)))
                .addEdge(START, "expert_debate")
                .addEdge("expert_debate", "cio_cross")
                .addEdge("cio_cross",     "cio_final")
                .addConditionalEdges("cio_final",
                        AsyncEdgeAction.edge_async(state -> {
                            int score = state.getScore();
                            int round = state.getRound();
                            if (score >= PASS_SCORE || round > MAX_DEBATE_ROUNDS) {
                                return END;
                            }
                            return "expert_debate";
                        }),
                        Map.of(END, END, "expert_debate", "expert_debate")
                )
                .compile();
    }

    // -------------------------------------------------------------------------
    // 图节点：expert_debate（三专家并行分析）
    // -------------------------------------------------------------------------

    /**
     * 三专家并行分析节点。
     * 第一轮：直接基于投研情报简报各自分析。
     * 第二轮起：在简报基础上附加CIO对该专家的定向质询，要求正面回应。
     * 并行调用使用 ForkJoinPool.commonPool()，超时30秒后取已完成结果降级。
     *
     * @param state 当前辩论状态
     * @return 更新Map，覆盖 bullishReport / riskReport / quantReport，并清空 crossExamination
     * @throws Exception 节点执行异常时抛出（已内部降级，通常不抛）
     */
    public Map<String, Object> expertDebateNode(DebateState state, Consumer<String> heartbeat) throws Exception {
        int round = state.getRound();
        if (round <= 1) {
            heartbeat.accept("[专家团] 第1轮辩论启动 · 多头分析师、风控师、量化策略师正在并行研判...");
        } else {
            heartbeat.accept("[专家团] 第" + round + "轮辩论 · CIO质询已下达，三位分析师正在回应...");
        }
        String brief     = state.getBrief();
        String crossExam = state.getCrossExamination();

        // 基础Prompt（每个专家共用）
        String basePrompt = "以下是投研情报简报，请进行分析：\n" + brief;

        // 第二轮起追加CIO对该专家的定向质询，没有质询则使用基础Prompt
        String bullishPrompt = crossExam.isBlank() ? basePrompt
                : basePrompt + "\n\n【CIO质询，请正面回应】\n" + extractQuestion(crossExam, "BULLISH");
        String riskPrompt    = crossExam.isBlank() ? basePrompt
                : basePrompt + "\n\n【CIO质询，请正面回应】\n" + extractQuestion(crossExam, "RISK");
        String quantPrompt   = crossExam.isBlank() ? basePrompt
                : basePrompt + "\n\n【CIO质询，请正面回应】\n" + extractQuestion(crossExam, "QUANT");

        // 三专家并行调用
        CompletableFuture<String> bullishFuture = CompletableFuture.supplyAsync(
                () -> callLLM(SYSTEM_PROMPT_BULLISH, bullishPrompt));
        CompletableFuture<String> riskFuture    = CompletableFuture.supplyAsync(
                () -> callLLM(SYSTEM_PROMPT_RISK,    riskPrompt));
        CompletableFuture<String> quantFuture   = CompletableFuture.supplyAsync(
                () -> callLLM(SYSTEM_PROMPT_QUANT,   quantPrompt));

        try {
            CompletableFuture.allOf(bullishFuture, riskFuture, quantFuture).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("三专家并行调用超时或异常，使用已完成结果：{}", e.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bullishReport",    bullishFuture.getNow("{}"));
        result.put("riskReport",       riskFuture.getNow("{}"));
        result.put("quantReport",      quantFuture.getNow("{}"));
        result.put("crossExamination", ""); // 清空上一轮质询，避免下轮重复消费
        return result;
    }

    // -------------------------------------------------------------------------
    // 图节点：cio_cross（CIO提取矛盾，生成质询）
    // -------------------------------------------------------------------------

    /**
     * CIO质询节点：阅读三份专家报告，提取最核心矛盾点，生成定向质询JSON。
     *
     * @param state 当前辩论状态
     * @return 更新Map，覆盖 crossExamination
     */
    public Map<String, Object> cioCrossNode(DebateState state, Consumer<String> heartbeat) {
        heartbeat.accept("[CIO] 正在审阅三份报告，提取核心矛盾，生成定向质询...");
        String prompt = "三位分析师的报告如下，请提取矛盾点并质询：\n\n" +
                "【激进分析师】\n" + state.getBullishReport() + "\n\n" +
                "【风控师】\n"     + state.getRiskReport()    + "\n\n" +
                "【量化策略师】\n" + state.getQuantReport();

        String result = callLLM(SYSTEM_PROMPT_CIO_CROSS, prompt);
        return Map.of("crossExamination", result);
    }

    // -------------------------------------------------------------------------
    // 图节点：cio_final（CIO综合裁决）
    // -------------------------------------------------------------------------

    /**
     * CIO最终裁决节点：综合三份报告和质询记录做出裁决，解析score决定是否继续辩论。
     *
     * @param state 当前辩论状态
     * @return 更新Map，覆盖 cioResult / score，并将 round 自增1
     */
    public Map<String, Object> cioFinalNode(DebateState state, Consumer<String> heartbeat) {
        heartbeat.accept("[CIO] 综合三方观点与质询回应，正在作出最终裁决...");
        String prompt = "三位分析师报告及质询回应如下，请做出最终裁决：\n\n" +
                "【激进分析师报告】\n" + state.getBullishReport() + "\n\n" +
                "【风控师报告】\n"     + state.getRiskReport()    + "\n\n" +
                "【量化策略师报告】\n" + state.getQuantReport()   + "\n\n" +
                "【质询记录】\n"       + state.getCrossExamination();

        String result = callLLM(SYSTEM_PROMPT_CIO_FINAL, prompt);
        int score     = parseScore(result);

        log.info("CIO裁决完成，第{}轮，score={}", state.getRound(), score);

        Map<String, Object> update = new HashMap<>();
        update.put("cioResult", result);
        update.put("score",     score);
        update.put("round",     state.getRound() + 1);
        return update;
    }

    // -------------------------------------------------------------------------
    // 私有辅助
    // -------------------------------------------------------------------------

    /**
     * 非流式调用DeepSeek并剥离markdown代码块标签。
     * 内部捕获所有异常并返回空JSON字符串"{}"，节点方法不会因此抛异常中断图执行。
     *
     * @param systemPrompt 角色System Prompt
     * @param userPrompt   用户输入内容
     * @return LLM返回内容，或降级空JSON"{}"
     */
    private String callLLM(String systemPrompt, String userPrompt) {
        try {
            String result = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            if (result == null) {
                return "{}";
            }
            // 剥离LLM可能自作主张附加的```json ... ```围栏
            if (result.contains("```json")) {
                int start = result.indexOf("\n", result.indexOf("```json")) + 1;
                int end   = result.lastIndexOf("```");
                if (end > start) {
                    return result.substring(start, end).trim();
                }
            }
            return result.trim();
        } catch (Exception e) {
            log.warn("LLM调用失败，原因：{}", e.getMessage());
            return "{}";
        }
    }

    /**
     * 从CIO裁决JSON中解析综合评分。
     * 解析失败返回0，不中断流程（条件边将视为未达标继续重试或超轮数退出）。
     *
     * @param cioJson CIO裁决JSON字符串
     * @return 综合评分，解析失败为0
     */
    private int parseScore(String cioJson) {
        try {
            return objectMapper.readTree(cioJson).path("score").asInt(0);
        } catch (Exception e) {
            log.warn("CIO评分解析失败：{}", e.getMessage());
            return 0;
        }
    }

    /**
     * 从CIO质询JSON中提取针对指定角色的质询问题。
     * 若该角色没有被质询，返回空字符串（专家将基于原始简报正常分析）。
     *
     * @param crossExamJson cioCrossNode生成的质询JSON字符串
     * @param role          目标角色：BULLISH / RISK / QUANT
     * @return 质询问题文本，未被质询时返回空字符串
     */
    private String extractQuestion(String crossExamJson, String role) {
        try {
            var node           = objectMapper.readTree(crossExamJson);
            var contradictions = node.path("contradictions");
            if (contradictions.isArray()) {
                for (var item : contradictions) {
                    if (role.equals(item.path("targetRole").asText())) {
                        return item.path("question").asText("");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("质询内容解析失败，role={}，原因：{}", role, e.getMessage());
        }
        return "";
    }
}
