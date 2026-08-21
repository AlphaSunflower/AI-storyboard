package com.moon.moonagent.ai.agent;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.ai.AgentAiConfigProperties;
import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
import com.moon.moonagent.ai.agent.handler.IntentHandler;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.mapper.AgentMessageMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * PlanGraph —— StateGraph 主图（P2 骨架，2026-08-21）。
 *
 * <p>以 StateGraph 为核心工作流引擎：声明式节点 + 条件边替代手写 if/else 分发。
 * 节点 = 业务动作（意图识别 / 各意图链 handler 适配器），条件边 = 路由（含切链出口）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>SSE 输出在 handler/Support 内部用 emitter 直发</b>，图只管编排路由——invoke 一次
 *       = 处理一条消息（run），节点内 handler 发完卡片/消息即返回，图到 END</li>
 *   <li><b>切链出口</b>：条件边路由函数读 state（intent/action），可回 {@code intent_recognize}
 *       重新分发（P3 resume 路由化后，任意卡片节点都能切链）</li>
 *   <li><b>低置信度 → intent_clarify 卡片节点</b>（复用 runHITLStage），选项 id = intentType，
 *       resume 时按所选意图重新分发</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PlanGraph {

    private static final Logger log = LoggerFactory.getLogger(PlanGraph.class);

    /** state 键（Replace 策略——每轮新消息覆盖旧值） */
    private static final String K_CONVERSATION = "conversation";
    private static final String K_CONTENT = "content";
    private static final String K_PIC_URL = "picUrl";
    private static final String K_EMITTER = "emitter";
    private static final String K_INTENT = "intent";
    private static final String K_CONFIDENCE = "confidence";
    private static final String K_LOW_CONFIDENCE = "lowConfidence";
    private static final String K_LAST_MESSAGE = "lastMessage";

    private final IntentRecognitionService intentRecognitionService;
    private final AgentMessageMapper messageMapper;
    private final AgentAiConfigProperties agentConfig;
    private final AgentOrchestratorSupport support;
    private final List<IntentHandler> intentHandlers;

    /** intent → handler 适配器节点名（node_aisplit / node_pic / ...） */
    private Map<String, String> intentToNode = Map.of();
    /** intent → handler（P3 前临时：节点内取 handler 用） */
    private Map<String, IntentHandler> byIntent = Map.of();

    private CompiledGraph compiledGraph;

    @PostConstruct
    void build() throws Exception {
        // intent → 节点名 + handler 注册表
        Map<String, String> nodeMap = new HashMap<>();
        Map<String, IntentHandler> handlerMap = new HashMap<>();
        for (IntentHandler handler : intentHandlers) {
            handlerMap.put(handler.intentType(), handler);
            nodeMap.put(handler.intentType(), "node_" + handler.intentType().replace("intent-", ""));
        }
        byIntent = handlerMap;
        intentToNode = nodeMap;
        log.info("PlanGraph: intentToNode={}", intentToNode.keySet());

        KeyStrategyFactory strategies = () -> {
            Map<String, KeyStrategy> m = new HashMap<>();
            m.put(K_CONVERSATION, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
            m.put(K_CONTENT, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
            m.put(K_PIC_URL, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
            m.put(K_EMITTER, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
            m.put(K_INTENT, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
            m.put(K_CONFIDENCE, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
            m.put(K_LOW_CONFIDENCE, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
            m.put(K_LAST_MESSAGE, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
            return m;
        };

        StateGraph graph = new StateGraph("plan-graph", strategies)
                // ---- 节点 ----
                // 意图识别：查历史 + 规则前置 + LLM → state.intent / lowConfidence
                .addNode("intent_recognize", node_async(state -> {
                    AgentConversation conversation = state.<AgentConversation>value(K_CONVERSATION).orElseThrow();
                    String content = state.<String>value(K_CONTENT).orElse("");
                    List<AgentMessage> recent = messageMapper.selectList(
                            new LambdaQueryWrapper<AgentMessage>()
                                    .eq(AgentMessage::getConversationId, conversation.getId())
                                    .orderByDesc(AgentMessage::getCreatedAt)
                                    .last("LIMIT " + IntentRecognitionService.HISTORY_LIMIT)).reversed();
                    IntentResult intentResult = intentRecognitionService.recognize(content, recent);
                    log.info("PlanGraph: conversationId={} intent={} confidence={} source={}",
                            conversation.getId(), intentResult.type(), intentResult.confidence(), intentResult.source());
                    // 非 aisplit/pic 轮清零澄清计数
                    if (!"intent-aisplit".equals(intentResult.type()) && !"intent-pic".equals(intentResult.type())) {
                        support.resetClarify(conversation.getId());
                    }
                    return Map.of(
                            K_INTENT, intentResult.type(),
                            K_CONFIDENCE, intentResult.confidence(),
                            K_LOW_CONFIDENCE, intentResult.confidence() < agentConfig.getIntentThreshold());
                }))
                // 低置信度澄清卡片节点（复用 runHITLStage，选项 id = intentType）
                .addNode("intent_clarify", node_async(state -> {
                    OrchestrationRequest request = stateRequest(state);
                    String content = state.<String>value(K_CONTENT).orElse("");
                    String last = support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                            "没太确定你想做什么，请选择：",
                            "intent-clarify",
                            List.of(Map.of("content", content)),
                            "human_input",
                            List.of(
                                    Map.of("id", "intent-aisplit", "title", "生成分镜"),
                                    Map.of("id", "intent-pic", "title", "生成图片"),
                                    Map.of("id", "intent-video", "title", "生成视频"),
                                    Map.of("id", "intent-delete", "title", "删除分镜"),
                                    Map.of("id", "intent-other", "title", "其他 / 继续输入"),
                                    Map.of("id", "custom", "title", "✍ 自定义输入"))));
                    return Map.of(K_LAST_MESSAGE, last);
                }))
                // ---- 条件边：意图路由（低置信度 → 澄清卡片；否则 → 对应 handler 节点） ----
                .addConditionalEdges("intent_recognize",
                        edge_async(state -> {
                            if (Boolean.TRUE.equals(state.value(K_LOW_CONFIDENCE).orElse(false))) {
                                return "intent_clarify";
                            }
                            String intent = state.<String>value(K_INTENT).orElse(IntentRecognitionService.FALLBACK_TYPE);
                            return intentToNode.getOrDefault(intent,
                                    intentToNode.get(IntentRecognitionService.FALLBACK_TYPE));
                        }),
                        allNodeTargets())
                .addEdge(START, "intent_recognize")
                .addEdge("intent_clarify", END);

        // handler 适配器节点：node_aisplit / node_pic / ... → handler.handle(request)
        for (IntentHandler handler : intentHandlers) {
            String nodeName = "node_" + handler.intentType().replace("intent-", "");
            graph.addNode(nodeName, node_async(state -> {
                OrchestrationRequest request = stateRequest(state);
                String last = handler.handle(request);
                return Map.of(K_LAST_MESSAGE, last);
            }));
            graph.addEdge(nodeName, END);
        }

        compiledGraph = graph.compile();
        log.info("PlanGraph: 编译完成，节点={}", intentToNode.keySet());
    }

    /**
     * 处理一条用户消息（run 路径）：{@code content} → 意图识别 → 条件边分发 → handler.handle。
     *
     * @return 本轮最后一条 message 内容（供调用方落库 assistant 消息）
     */
    public String run(AgentConversation conversation, String content, String picUrl, SseEmitter emitter) {
        RunnableConfig config = RunnableConfig.builder().threadId(conversation.getId()).build();
        Optional<OverAllState> result = compiledGraph.invoke(Map.of(
                K_CONVERSATION, conversation,
                K_CONTENT, content,
                K_PIC_URL, picUrl == null ? "" : picUrl,
                K_EMITTER, emitter,
                K_LAST_MESSAGE, ""), config);
        return result.flatMap(s -> s.<String>value(K_LAST_MESSAGE)).orElse("");
    }

    /** state → OrchestrationRequest（handler 适配器用） */
    private OrchestrationRequest stateRequest(OverAllState state) {
        AgentConversation conversation = state.<AgentConversation>value(K_CONVERSATION).orElseThrow();
        String content = state.<String>value(K_CONTENT).orElse("");
        String picUrl = state.<String>value(K_PIC_URL).orElse("");
        SseEmitter emitter = state.<SseEmitter>value(K_EMITTER).orElseThrow();
        return new OrchestrationRequest(conversation, content,
                picUrl == null || picUrl.isBlank() ? null : picUrl, emitter);
    }

    /** 条件边输出 → 节点映射（覆盖路由函数全部可能返回值：intent_clarify + 各 handler 节点 + 兜底） */
    private Map<String, String> allNodeTargets() {
        Map<String, String> m = new HashMap<>();
        m.put("intent_clarify", "intent_clarify");
        for (String node : intentToNode.values()) {
            m.put(node, node);
        }
        // 兜底节点（未知 intent → node_other 已在 intentToNode 中，无需额外）
        return m;
    }
}
