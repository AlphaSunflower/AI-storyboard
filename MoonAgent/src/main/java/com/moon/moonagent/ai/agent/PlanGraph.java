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
import com.moon.moonagent.entity.AgentCheckpoint;
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
 * PlanGraph —— StateGraph 主图（P2 骨架 + P3 resume 路由化，2026-08-21）。
 *
 * <p>以 StateGraph 为核心工作流引擎：声明式节点 + 条件边替代手写 if/else 分发。
 * 节点 = 业务动作（意图识别 / 各意图链 handler 适配器），条件边 = 路由（含切链出口）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>SSE 输出在 handler/Support 内部用 emitter 直发</b>，图只管编排路由——invoke 一次
 *       = 处理一条消息（run）或一次 HITL 表单提交（resume），节点内 handler 发完卡片/消息即返回，图到 END</li>
 *   <li><b>切链出口</b>：run 路径条件边读 state（intent/action）；resume 路径条件边读
 *       cpAction/提交 action/customText——自定义文本识别为新意图 → 回 {@code intent_recognize} 重新分发
 *       （P3：任意卡片节点都能切链，用户中途改主意不再被卡死在当前链）</li>
 *   <li><b>低置信度 → intent_clarify 卡片节点</b>（复用 runHITLStage），选项 id = intentType</li>
 * </ul>
 *
 * <p>resume 路由（P3，替代 AgentOrchestratorImpl 12+ if/else）：
 * <pre>
 * resume_route（条件边，读 state）
 *   ├─ customText 非空且意图 ≠ 当前链 → intent_recognize（★切链出口）
 *   ├─ cpAction=intent-clarify → 按所选 action 重新 handle / custom → 重新 run
 *   ├─ cpAction=asset-selection|asset-gate → 按 plan.source → video / scene-review / aisplit
 *   ├─ 提交 action=custom → 按 cpAction：generate_image→pic、generate_video|video-opinion→video
 *   └─ 其余 cpAction/action → 静态映射表（clarify-option/scene-mode/... → aisplit 等）或 byAction 注册表
 * </pre>
 */
@Component
@RequiredArgsConstructor
public class PlanGraph {

    private static final Logger log = LoggerFactory.getLogger(PlanGraph.class);

    /** state 键（Replace 策略——每轮新消息覆盖旧值） */
    private static final String K_CONVERSATION = "conversation";
    private static final String K_CONTENT = "content";
    private static final String K_PIC_URL = "picUrl";
    private static final String K_INTENT = "intent";
    private static final String K_CONFIDENCE = "confidence";
    private static final String K_LOW_CONFIDENCE = "lowConfidence";
    private static final String K_LAST_MESSAGE = "lastMessage";
    // P3 resume 键
    private static final String K_RESUME = "resume";            // true=resume 路径（HITL 表单提交）
    private static final String K_CP_ACTION = "cpAction";       // checkpoint.action（卡片类型）
    private static final String K_CP_PLAN = "cpPlan";           // checkpoint.plan（方案 JSON）
    private static final String K_SUBMIT_ACTION = "submitAction"; // 用户提交的选项 id
    private static final String K_CUSTOM_TEXT = "customText";   // 自定义输入文本（action=custom 时）
    private static final String K_PARAMS = "params";            // 生成参数（model/resolution/...）
    private static final String K_ASSET_IDS = "assetIds";       // 资产选择卡片勾选
    private static final String K_SWITCH = "switch";            // 切链出口标记：重路由 intent_recognize
    private static final String K_ROUTING_HINT = "routingHint"; // 前端显式切链提示（目标 intentType，优先级最高）

    private final IntentRecognitionService intentRecognitionService;
    private final AgentMessageMapper messageMapper;
    private final AgentAiConfigProperties agentConfig;
    private final AgentOrchestratorSupport support;
    private final List<IntentHandler> intentHandlers;

    /**
     * emitter 外部注册表：threadId(=conversationId) → SseEmitter。
     * ★ 不能放进 OverAllState——graph-core invoke 会对 state 做序列化克隆（cloneState），
     * SseEmitter 克隆后身份改变，send 到克隆对象上 Tomcat 收不到 → SSE 空流（2026-08-21 实测根因）
     */
    private final java.util.concurrent.ConcurrentHashMap<String, SseEmitter> emittersByThread =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** intent → handler 适配器节点名（node_aisplit / node_pic / ...） */
    private Map<String, String> intentToNode = Map.of();
    /** intent → handler */
    private Map<String, IntentHandler> byIntent = Map.of();
    /** resume 提交 action → handler（由各 handler 的 resumeActions 展开） */
    private Map<String, IntentHandler> byAction = Map.of();

    /** P3 静态映射：checkpoint.action → 目标 intent（其余走动态特判/byAction） */
    private static final Map<String, String> CP_ACTION_TO_INTENT = Map.of(
            "clarify-option", "intent-aisplit",
            "scene-mode", "intent-aisplit",
            "requirement-clarify", "intent-aisplit",
            "scene-regenerate", "intent-aisplit",
            "video-clarify", "intent-video",
            "pic-option", "intent-pic",
            "pic-clarify", "intent-pic",
            "delete-confirm", "intent-delete");

    private CompiledGraph compiledGraph;

    @PostConstruct
    void build() throws Exception {
        // intent → 节点名 + handler 注册表（byIntent / byAction 双注册，与旧 Orchestrator 同语义）
        Map<String, String> nodeMap = new HashMap<>();
        Map<String, IntentHandler> handlerMap = new HashMap<>();
        Map<String, IntentHandler> actionMap = new HashMap<>();
        for (IntentHandler handler : intentHandlers) {
            handlerMap.put(handler.intentType(), handler);
            nodeMap.put(handler.intentType(), "node_" + handler.intentType().replace("intent-", ""));
            for (String action : handler.resumeActions()) {
                actionMap.put(action, handler);
            }
        }
        byIntent = handlerMap;
        byAction = actionMap;
        intentToNode = nodeMap;
        log.info("PlanGraph: intentToNode={} resumeActions={}", intentToNode.keySet(), actionMap.keySet());

        KeyStrategyFactory strategies = () -> {
            Map<String, KeyStrategy> m = new HashMap<>();
            m.put(K_CONVERSATION, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
            m.put(K_CONTENT, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
            m.put(K_PIC_URL, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
            m.put(K_INTENT, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
            m.put(K_CONFIDENCE, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
            m.put(K_LOW_CONFIDENCE, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
            m.put(K_LAST_MESSAGE, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
            m.put(K_RESUME, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
            m.put(K_CP_ACTION, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
            m.put(K_CP_PLAN, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
            m.put(K_SUBMIT_ACTION, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
            m.put(K_CUSTOM_TEXT, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
            m.put(K_PARAMS, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
            m.put(K_ASSET_IDS, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
            m.put(K_SWITCH, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
            m.put(K_ROUTING_HINT, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
            return m;
        };

        StateGraph graph = new StateGraph("plan-graph", strategies)
                // ---- 入口分流节点：K_RESUME=true → resume_route；否则 → intent_recognize ----
                .addNode("entry", node_async(state -> Map.of()))
                // ---- 意图识别节点（run 路径入口） ----
                .addNode("intent_recognize", node_async(state -> {
                    AgentConversation conversation = state.<AgentConversation>value(K_CONVERSATION).orElseThrow();
                    String content = state.<String>value(K_CONTENT).orElse("");
                    // 发送"正在理解你的需求…"workflow 事件，避免意图识别期间前端只显示"正在生成"
                    SseEmitter emitter = emittersByThread.get(conversation.getId());
                    if (emitter != null) {
                        try { emitter.send(SseEmitter.event().name("workflow").data(Map.of("title", "正在理解你的需求…", "status", "node_started"))); } catch (Exception ignored) {}
                    }
                    List<AgentMessage> recent = messageMapper.selectList(
                            new LambdaQueryWrapper<AgentMessage>()
                                    .eq(AgentMessage::getConversationId, conversation.getId())
                                    .orderByDesc(AgentMessage::getCreatedAt)
                                    .last("LIMIT " + IntentRecognitionService.HISTORY_LIMIT)).reversed();
                    IntentResult intentResult = intentRecognitionService.recognize(content, recent);
                    log.info("PlanGraph: conversationId={} intent={} confidence={} source={}",
                            conversation.getId(), intentResult.type(), intentResult.confidence(), intentResult.source());
                    if (!"intent-aisplit".equals(intentResult.type()) && !"intent-pic".equals(intentResult.type())) {
                        support.resetClarify(conversation.getId());
                    }
                    return Map.of(
                            K_INTENT, intentResult.type(),
                            K_CONFIDENCE, intentResult.confidence(),
                            K_LOW_CONFIDENCE, intentResult.confidence() < agentConfig.getIntentThreshold(),
                            K_SWITCH, false);
                }))
                // ---- 低置信度澄清卡片节点 ----
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
                                    Map.of("id", "intent-scene-review", "title", "查看分镜"),
                                    Map.of("id", "intent-delete", "title", "删除分镜"),
                                    Map.of("id", "intent-other", "title", "其他 / 继续输入"),
                                    Map.of("id", "custom", "title", "✍ 自定义输入"))));
                    return Map.of(K_LAST_MESSAGE, last);
                }))
                // ---- 切链出口节点（resume 检测到新意图时：用 customText 重新走 run 编排） ----
                .addNode("switch_to_run", node_async(state -> {
                    // 把自定义文本作为新用户消息，清 resume 标记 → intent_recognize 重新识别
                    String customText = state.<String>value(K_CUSTOM_TEXT).orElse("");
                    return Map.of(
                            K_CONTENT, customText.isBlank() ? state.<String>value(K_CONTENT).orElse("") : customText,
                            K_RESUME, false,
                            K_CP_ACTION, "",
                            K_SUBMIT_ACTION, "");
                }))
                // ---- intent-clarify 点选目标意图 / 显式 routingHint 切链：清 resume 标记 + 锁定意图 → 走 run 路由（重新 handle，非 resume） ----
                .addNode("rerun_selected", node_async(state -> {
                    String hint = state.<String>value(K_ROUTING_HINT).orElse("");
                    String selected = hint.isBlank()
                            ? state.<String>value(K_SUBMIT_ACTION).orElse(IntentRecognitionService.FALLBACK_TYPE)
                            : hint;
                    return Map.of(
                            K_INTENT, selected,
                            K_CONFIDENCE, 1.0,
                            K_LOW_CONFIDENCE, false,
                            K_RESUME, false);
                }))
                // ---- resume 路由节点：读 checkpoint.action / 提交 action / customText → 条件边分发 ----
                .addNode("resume_route", node_async(state -> Map.of()))
                // ---- 条件边：run 入口路由（低置信度 → 澄清卡片；否则 → handler 节点） ----
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
                // ---- 条件边：resume 路由（P3 核心：替代 12+ if/else） ----
                .addConditionalEdges("resume_route",
                        edge_async(state -> {
                            String cpAction = state.<String>value(K_CP_ACTION).orElse("");
                            String submitAction = state.<String>value(K_SUBMIT_ACTION).orElse("");
                            String customText = state.<String>value(K_CUSTOM_TEXT).orElse("");
                            // 当前链：由 cpAction 推导（CP_ACTION_TO_INTENT 静态映射 + 动态特判）
                            String intent = cpIntent(cpAction);
                            // ★★ 显式 routingHint（前端「换个话题」等按钮）优先——免去 customText 语义意图识别误判：
                            // 直达目标链（rerun_selected 锁 intent 走 handle），把切链主动权交还前端（人在回路）
                            String routingHint = state.<String>value(K_ROUTING_HINT).orElse("");
                            if (!routingHint.isBlank()) {
                                log.info("PlanGraph: resume 显式 routingHint={} currentIntent={} → rerun_selected", routingHint, intent);
                                return "rerun_selected";
                            }

                            // ★ 切链出口：customText 非空 → 意图识别 → 意图 ≠ 当前链 → 回 intent_recognize
                            if (!customText.isBlank()) {
                                List<AgentMessage> recent = messageMapper.selectList(
                                        new LambdaQueryWrapper<AgentMessage>()
                                                .eq(AgentMessage::getConversationId,
                                                        state.<AgentConversation>value(K_CONVERSATION).orElseThrow().getId())
                                                .orderByDesc(AgentMessage::getCreatedAt)
                                                .last("LIMIT " + IntentRecognitionService.HISTORY_LIMIT)).reversed();
                                IntentResult ir = intentRecognitionService.recognize(customText, recent);
                                String target = intentToNode.getOrDefault(ir.type(),
                                        intentToNode.get(IntentRecognitionService.FALLBACK_TYPE));
                                log.info("PlanGraph: resume 切链检测 customText intent={} confidence={} currentIntent={} → {}",
                                        ir.type(), ir.confidence(), intent, target);
                                // 意图明确（规则命中或高置信度）且不是当前链 → 切链
                                if ((ir.source().equals("rule") || ir.confidence() >= agentConfig.getIntentThreshold())
                                        && !ir.type().equals(intent)) {
                                    return "switch_to_run";
                                }
                                // 否则按当前链的 custom 语义处理（调整意见/自定义方向），落到下方常规路由
                            }

                            // intent-clarify：用户点选目标意图 → 重新 handle（rerun_selected 清 resume 标记 + 锁定意图）；custom → 重新 run（意图识别）
                            if ("intent-clarify".equals(cpAction)) {
                                if ("custom".equals(submitAction)) {
                                    return "switch_to_run";
                                }
                                return "rerun_selected";
                            }
                            // asset-selection / asset-gate：按 plan.source 分派
                            if ("asset-selection".equals(cpAction) || "asset-gate".equals(cpAction)) {
                                String source = support.planField(state.<String>value(K_CP_PLAN).orElse(""), "source");
                                if ("video".equals(source)) return "node_video";
                                if ("review-video".equals(source)) return "node_scene_review";
                                return "node_aisplit";
                            }
                            // 提交 action=custom：按 cpAction 分派（generate_image→pic；generate_video/video-opinion→video）
                            if ("custom".equals(submitAction)) {
                                if ("generate_image".equals(cpAction)) return "node_pic";
                                if ("generate_video".equals(cpAction) || "video-opinion".equals(cpAction)) return "node_video";
                            }
                            // 静态映射表：checkpoint.action → 目标 intent 节点
                            String mappedIntent = CP_ACTION_TO_INTENT.get(cpAction);
                            if (mappedIntent != null) {
                                return intentToNode.getOrDefault(mappedIntent,
                                        intentToNode.get(IntentRecognitionService.FALLBACK_TYPE));
                            }
                            // byAction 注册表：agree/replace/append/cancel/disagree 等固定选项
                            IntentHandler handler = byAction.get(submitAction);
                            if (handler != null) {
                                return intentToNode.getOrDefault(handler.intentType(),
                                        intentToNode.get(IntentRecognitionService.FALLBACK_TYPE));
                            }
                            // 未知 action 兜底：直接 END（保持旧语义：回一条 message + message_end）
                            log.warn("PlanGraph: resume 未知路由 cpAction={} submitAction={} → END", cpAction, submitAction);
                            return "END";
                        }),
                        allNodeTargetsWithEnd())
                .addConditionalEdges("entry",
                        edge_async(state -> Boolean.TRUE.equals(state.value(K_RESUME).orElse(false))
                                ? "resume_route" : "intent_recognize"),
                        Map.of("resume_route", "resume_route", "intent_recognize", "intent_recognize"))
                .addEdge(START, "entry")
                .addEdge("intent_clarify", END)
                .addEdge("switch_to_run", "intent_recognize")
                // rerun_selected：锁定意图后走 run 路由（同 intent_recognize 的条件边，低置信度恒 false → 直达指定 handler）
                .addConditionalEdges("rerun_selected",
                        edge_async(state -> {
                            String intent = state.<String>value(K_INTENT).orElse(IntentRecognitionService.FALLBACK_TYPE);
                            return intentToNode.getOrDefault(intent,
                                    intentToNode.get(IntentRecognitionService.FALLBACK_TYPE));
                        }),
                        allNodeTargets());

        // handler 适配器节点：node_aisplit / node_pic / ... → handler.handle(request) 或 handler.resume(request, cp)
        for (IntentHandler handler : intentHandlers) {
            String nodeName = "node_" + handler.intentType().replace("intent-", "");
            graph.addNode(nodeName, node_async(state -> {
                OrchestrationRequest request = stateRequest(state);
                // resume 路径：从 state 标量重建 checkpoint（handler 只用 getAction/getPlan）
                if (Boolean.TRUE.equals(state.value(K_RESUME).orElse(false))) {
                    request.setAction(state.<String>value(K_SUBMIT_ACTION).orElse(""));
                    request.setCustomText(state.<String>value(K_CUSTOM_TEXT).orElse(""));
                    request.setParams(state.<Map<String, String>>value(K_PARAMS).orElse(Map.of()));
                    request.setAssetIds(state.<List<String>>value(K_ASSET_IDS).orElse(List.of()));
                    AgentCheckpoint cp = new AgentCheckpoint();
                    cp.setAction(state.<String>value(K_CP_ACTION).orElse(""));
                    cp.setPlan(state.<String>value(K_CP_PLAN).orElse(""));
                    String last = handler.resume(request, cp);
                    return Map.of(K_LAST_MESSAGE, last);
                }
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
        emittersByThread.put(conversation.getId(), emitter);
        try {
            RunnableConfig config = RunnableConfig.builder().threadId(conversation.getId()).build();
            Optional<OverAllState> result = compiledGraph.invoke(Map.of(
                    K_CONVERSATION, conversation,
                    K_CONTENT, content,
                    K_PIC_URL, picUrl == null ? "" : picUrl,
                    K_RESUME, false,
                    K_LAST_MESSAGE, ""), config);
            return result.flatMap(s -> s.<String>value(K_LAST_MESSAGE)).orElse("");
        } finally {
            emittersByThread.remove(conversation.getId());
        }
    }

    /**
     * 处理一次 HITL 表单提交（resume 路径）：checkpoint 已由调用方校验+一次性消费，
     * 这里按 checkpoint.action / 提交 action / customText 条件边路由到对应 handler.resume。
     *
     * @return 本轮最后一条 message 内容（供调用方落库 assistant 消息）
     */
    public String resume(AgentConversation conversation, AgentCheckpoint checkpoint,
                         String action, String customText, Map<String, String> params,
                         java.util.List<String> assetIds, String routingHint, SseEmitter emitter) {
        emittersByThread.put(conversation.getId(), emitter);
        try {
            RunnableConfig config = RunnableConfig.builder().threadId(conversation.getId()).build();
            Map<String, Object> inputs = new HashMap<>();
            inputs.put(K_CONVERSATION, conversation);
            inputs.put(K_CONTENT, support.planField(checkpoint.getPlan(), "content"));
            inputs.put(K_RESUME, true);
            inputs.put(K_CP_ACTION, checkpoint.getAction() == null ? "" : checkpoint.getAction());
            inputs.put(K_CP_PLAN, checkpoint.getPlan() == null ? "" : checkpoint.getPlan());
            inputs.put(K_SUBMIT_ACTION, action == null ? "" : action);
            inputs.put(K_CUSTOM_TEXT, customText == null ? "" : customText);
            inputs.put(K_PARAMS, params == null ? Map.of() : params);
            inputs.put(K_ASSET_IDS, assetIds == null ? List.of() : assetIds);
            inputs.put(K_ROUTING_HINT, routingHint == null ? "" : routingHint);
            inputs.put(K_LAST_MESSAGE, "");
            Optional<OverAllState> result = compiledGraph.invoke(inputs, config);
            return result.flatMap(s -> s.<String>value(K_LAST_MESSAGE)).orElse("");
        } finally {
            emittersByThread.remove(conversation.getId());
        }
    }

    /** cpAction → 当前链意图（切链检测的"当前链"判定；动态特判按常见语义归组） */
    private String cpIntent(String cpAction) {
        if (cpAction == null || cpAction.isBlank()) return "";
        if (CP_ACTION_TO_INTENT.containsKey(cpAction)) return CP_ACTION_TO_INTENT.get(cpAction);
        return switch (cpAction) {
            case "generate_image", "pic-option", "pic-clarify" -> "intent-pic";
            case "generate_video", "video-opinion", "video-clarify" -> "intent-video";
            case "delete-confirm" -> "intent-delete";
            default -> ""; // asset-selection/asset-gate/intent-clarify 等：由其他分支处理
        };
    }

    /** state → OrchestrationRequest（handler 适配器用） */
    private OrchestrationRequest stateRequest(OverAllState state) {
        AgentConversation conversation = state.<AgentConversation>value(K_CONVERSATION).orElseThrow();
        String content = state.<String>value(K_CONTENT).orElse("");
        String picUrl = state.<String>value(K_PIC_URL).orElse("");
        SseEmitter emitter = emittersByThread.get(conversation.getId());
        if (emitter == null) {
            throw new IllegalStateException("PlanGraph: emitter 未注册 conversationId=" + conversation.getId());
        }
        return new OrchestrationRequest(conversation, content,
                picUrl == null || picUrl.isBlank() ? null : picUrl, emitter);
    }

    /** 条件边输出 → 节点映射（run 路由：intent_clarify + 各 handler 节点） */
    private Map<String, String> allNodeTargets() {
        Map<String, String> m = new HashMap<>();
        m.put("intent_clarify", "intent_clarify");
        for (String node : intentToNode.values()) {
            m.put(node, node);
        }
        return m;
    }

    /** 条件边输出 → 节点映射（resume 路由：handler 节点 + switch_to_run + rerun_selected + END） */
    private Map<String, String> allNodeTargetsWithEnd() {
        Map<String, String> m = allNodeTargets();
        m.put("switch_to_run", "switch_to_run");
        m.put("rerun_selected", "rerun_selected");
        m.put("END", END);
        return m;
    }
}
