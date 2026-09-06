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
import com.moon.moonagent.ai.agent.handler.AgentTraceAccumulator;
import com.moon.moonagent.ai.agent.handler.IntentHandler;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.agent.specialist.SpecialistAgent;
import com.moon.moonagent.ai.agent.specialist.SpecialistAgentRegistry;
import com.moon.moonagent.ai.agent.specialist.SpecialistContext;
import com.moon.moonagent.ai.agent.specialist.SpecialistResult;
import com.moon.moonagent.ai.agent.skill.AgentSkill;
import com.moon.moonagent.ai.agent.skill.SkillManager;
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
    private static final String K_PIC_URLS = "picUrls";
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
    // Phase 3: 移交状态键
    private static final String K_HANDOFF_TARGET = "handoffTarget"; // 移交目标 intentType（null=完成）
    private static final String K_HANDOFF_STATE = "handoffState";   // 移交传递的状态

    private final IntentRecognitionService intentRecognitionService;
    private final AgentMessageMapper messageMapper;
    private final AgentAiConfigProperties agentConfig;
    private final AgentOrchestratorSupport support;
    private final List<IntentHandler> intentHandlers;
    private final SkillManager skillManager;
    private final SpecialistAgentRegistry specialistRegistry;
    private final com.moon.moonagent.ai.agent.handler.RouteChangeService routeChangeService;
    private final com.moon.moonagent.ai.agent.handler.RouteTaskCancellationService routeTaskCancellationService;

    /**
     * emitter 外部注册表：threadId(=conversationId) → SseEmitter。
     * ★ 不能放进 OverAllState——graph-core invoke 会对 state 做序列化克隆（cloneState），
     * SseEmitter 克隆后身份改变，send 到克隆对象上 Tomcat 收不到 → SSE 空流（2026-08-21 实测根因）
     */
    private final java.util.concurrent.ConcurrentHashMap<String, SseEmitter> emittersByThread =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 一轮轨迹累积器注册表：threadId(=conversationId) → AgentTraceAccumulator（不参与状态序列化）。 */
    private final java.util.concurrent.ConcurrentHashMap<String, AgentTraceAccumulator> traceByThread =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 本轮最后一个 OrchestrationRequest（供 runTurn/resumeTurn 读 hitlPaused/metadata/suggestedActions）。 */
    private final java.util.concurrent.ConcurrentHashMap<String, OrchestrationRequest> requestsByThread =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** intent → handler 适配器节点名（node_aisplit / node_pic / ...） */
    private Map<String, String> intentToNode = Map.of();
    /** intent → handler */
    private Map<String, IntentHandler> byIntent = Map.of();
    /** resume 提交 action → handler（由各 handler 的 resumeActions 展开） */
    private Map<String, IntentHandler> byAction = Map.of();

    /** P3 静态映射：checkpoint.action → 目标 intent（其余走动态特判/byAction） */
    private static final Map<String, String> CP_ACTION_TO_INTENT = Map.ofEntries(
            Map.entry("clarify-option", "intent-aisplit"),
            Map.entry("scene-mode", "intent-aisplit"),
            Map.entry("requirement-clarify", "intent-aisplit"),
            Map.entry("scene-regenerate", "intent-aisplit"),
            Map.entry("scene-select", "intent-aisplit"),
            Map.entry("script-source", "intent-aisplit"),
            Map.entry("video-clarify", "intent-video"),
            Map.entry("video-source", "intent-video"),
            Map.entry("video-role-select", "intent-video"),
            Map.entry("pic-option", "intent-pic"),
            Map.entry("pic-clarify", "intent-pic"),
            Map.entry("prompt-source", "intent-pic"),
            Map.entry("delete-confirm", "intent-delete"));

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
            m.put(K_PIC_URLS, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
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
            m.put(K_HANDOFF_TARGET, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
            m.put(K_HANDOFF_STATE, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
            return m;
        };

        StateGraph graph = new StateGraph("plan-graph", strategies)
                // ---- 入口分流节点：K_RESUME=true → resume_route；否则 → intent_recognize ----
                .addNode("entry", node_async(state -> Map.of()))
                // ---- 意图识别节点（run 路径入口） ----
                .addNode("intent_recognize", node_async(state -> {
                    AgentConversation conversation = state.<AgentConversation>value(K_CONVERSATION).orElseThrow();
                    String content = state.<String>value(K_CONTENT).orElse("");
                    String routingHint = state.<String>value(K_ROUTING_HINT).orElse("");
                    AgentTraceAccumulator trace = traceByThread.get(conversation.getId());
                    // 显式 routingHint（前端推荐动作/切链按钮）：绕过意图模型，直接锁定目标意图
                    if (!routingHint.isBlank()) {
                        log.info("PlanGraph: run 显式 routingHint={} → 绕过意图模型锁定意图", routingHint);
                        support.resetClarify(conversation.getId());
                        if (trace != null) trace.add("route_change", "moon", "路由提示", "命中显式 routingHint，锁定意图 " + routingHint, "", routingHint, "route_changed");
                        return Map.of(
                                K_INTENT, routingHint,
                                K_CONFIDENCE, 1.0,
                                K_LOW_CONFIDENCE, false,
                                K_SWITCH, false);
                    }
                    // 发送"正在理解你的需求…"workflow 事件，避免意图识别期间前端只显示"正在生成"
                    SseEmitter emitter = emittersByThread.get(conversation.getId());
                    if (emitter != null) {
                        try { emitter.send(SseEmitter.event().name("workflow").data(AgentOrchestratorSupport.sseJson(Map.of("title", "正在理解你的需求…", "status", "node_started")))); } catch (Exception ignored) {}
                    }
                    List<AgentMessage> recent = messageMapper.selectList(
                            new LambdaQueryWrapper<AgentMessage>()
                                    .eq(AgentMessage::getConversationId, conversation.getId())
                                    .orderByDesc(AgentMessage::getCreatedAt)
                                    .last("LIMIT " + IntentRecognitionService.HISTORY_LIMIT)).reversed();
                    IntentResult intentResult = intentRecognitionService.recognize(content, recent);
                    log.info("PlanGraph: conversationId={} intent={} confidence={} source={}",
                            conversation.getId(), intentResult.type(), intentResult.confidence(), intentResult.source());
                    if (trace != null) trace.add("intent_recognize", "moon", "意图识别", "识别为 " + intentResult.type() + "，置信度 " + intentResult.confidence(), "", intentResult.type(), "node_completed");
                    if (!"intent-aisplit".equals(intentResult.type()) && !"intent-pic".equals(intentResult.type())) {
                        support.resetClarify(conversation.getId());
                    }
                    // 上下文意图延续（2026-08-28）：LLM 低置信度（结果不明确）且当前 query 是短表达
                    // （反悔/延续句，如「骗你的/我还是想生成/继续/做吧」）时，回看最近一条用户消息——
                    // 若它命中某个非 other 意图（如上一轮在分镜链路），就把当前 query 延续到该意图，
                    // 而不是弹「没太确定你想做什么」的澄清卡。让用户能通过上下文回到分镜链路。
                    boolean lowConf = intentResult.confidence() < agentConfig.getIntentThreshold();
                    if (lowConf && content != null && content.trim().length() <= 8) {
                        String anchorIntent = null;
                        for (int i = recent.size() - 1; i >= 0; i--) {
                            AgentMessage m = recent.get(i);
                            if (m != null && "user".equals(m.getRole())) {
                                anchorIntent = intentRecognitionService.ruleMatch(m.getContent());
                                break;
                            }
                        }
                        if (anchorIntent != null && !IntentRecognitionService.FALLBACK_TYPE.equals(anchorIntent)) {
                            log.info("PlanGraph: 上下文意图延续 {} -> {}（最近用户消息命中规则）", intentResult.type(), anchorIntent);
                            return Map.of(
                                    K_INTENT, anchorIntent,
                                    K_CONFIDENCE, 1.0,
                                    K_LOW_CONFIDENCE, false,
                            K_SWITCH, false);
                        }
                    }
                    // 分镜链续流（2026-09-05）：会话正在分镜澄清（pending checkpoint 或最近用户消息命中分镜），
                    // 且当前输入仍含「分镜/故事/剧情」、没有明确切视频/图片/查看/删除 → 锚定 intent-aisplit。
                    // 避免用户在「根据这个资产做一个多图转场」这类分镜澄清回复中被误拉去生成视频。
                    boolean recentAisplit = false;
                    for (AgentMessage m : recent) {
                        if (m == null || !"user".equals(m.getRole())) continue;
                        if ("intent-aisplit".equals(intentRecognitionService.ruleMatch(m.getContent()))) {
                            recentAisplit = true;
                            break;
                        }
                    }
                    String pendingAction = support.pendingCheckpointAction(conversation.getId());
                    if (!"intent-aisplit".equals(intentResult.type())
                            && AgentIntentContinuity.shouldContinueAisplit(content, pendingAction, recentAisplit)) {
                        log.info("PlanGraph: 分镜链上下文续流 -> intent-aisplit（pendingAction={}, recentAisplit={}）",
                                pendingAction, recentAisplit);
                        return Map.of(
                                K_INTENT, "intent-aisplit",
                                K_CONFIDENCE, 1.0,
                                K_LOW_CONFIDENCE, false,
                                K_SWITCH, false);
                    }
                    return Map.of(
                            K_INTENT, intentResult.type(),
                            K_CONFIDENCE, intentResult.confidence(),
                            K_LOW_CONFIDENCE, intentResult.confidence() < agentConfig.getIntentThreshold(),
                            K_SWITCH, false);
                }))
                // ---- 低置信度澄清卡片节点（菜单由 Skill 推导，新增能力自动出现） ----
                .addNode("intent_clarify", node_async(state -> {
                    OrchestrationRequest request = stateRequest(state);
                    String content = state.<String>value(K_CONTENT).orElse("");
                    java.util.List<Map<String, Object>> actions = new java.util.ArrayList<>();
                    for (var skill : skillManager.allMenuOrdered()) {
                        actions.add(Map.of("id", skill.intentType(), "title", skill.label()));
                    }
                    actions.add(Map.of("id", "custom", "title", "✍ 自定义输入"));
                    String last = support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                            "没太确定你想做什么，请选择：",
                            "intent-clarify",
                            List.of(Map.of("content", content)),
                            "human_input",
                            actions));
                    return Map.of(K_LAST_MESSAGE, last);
                }))
                // ---- 切链出口节点（resume 检测到新意图时：用 customText 重新走 run 编排） ----
                .addNode("switch_to_run", node_async(state -> {
                    // 把自定义文本作为新用户消息，清 resume 标记 → intent_recognize 重新识别
                    AgentConversation conv = state.<AgentConversation>value(K_CONVERSATION).orElseThrow();
                    String customText = state.<String>value(K_CUSTOM_TEXT).orElse("");
                    routeTaskCancellationService.cancelPendingTasks(conv.getId());
                    return Map.of(
                            K_CONTENT, customText.isBlank() ? state.<String>value(K_CONTENT).orElse("") : customText,
                            K_RESUME, false,
                            K_CP_ACTION, "",
                            K_SUBMIT_ACTION, "");
                }))
                // ---- intent-clarify 点选目标意图 / 显式 routingHint 切链：清 resume 标记 + 锁定意图 → 走 run 路由（重新 handle，非 resume） ----
                .addNode("rerun_selected", node_async(state -> {
                    AgentConversation conv = state.<AgentConversation>value(K_CONVERSATION).orElseThrow();
                    String hint = state.<String>value(K_ROUTING_HINT).orElse("");
                    String selected = hint.isBlank()
                            ? state.<String>value(K_SUBMIT_ACTION).orElse(IntentRecognitionService.FALLBACK_TYPE)
                            : hint;
                    routeTaskCancellationService.cancelPendingTasks(conv.getId());
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
                            return routeTarget(intent);
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

                            // scene-review 操作菜单的「自定义输入」保留切链能力（用户可在审查卡直接说「生成分镜/视频」）；
                            // 其余分镜/视频/图片澄清卡不自动切链，避免被内容词误判。
                            if ("review-action".equals(cpAction) && "custom".equals(submitAction)) {
                                return "switch_to_run";
                            }

                            // ★ 通用技能卡锚定：skill-confirm / skill-step:* 一律回 skill_manager，
                            // 自定义文本不再触发全局重新识别切链（避免多轮澄清跑偏）
                            if (cpAction != null && cpAction.startsWith("skill-")) {
                                log.info("PlanGraph: resume 通用技能卡 cpAction={} → skill_manager（同链锚定）", cpAction);
                                return "skill_manager";
                            }

                            // ★ 切链出口（2026-09-06 收紧）：链内澄清卡的自定义输入一律留在当前链。
                            // 只有「intent-clarify」全局意图菜单、前端显式 routingHint、以及命中显式切链短语
                            // （routeChangeService）才允许切链，否则用户在分镜澄清里输入「根据这个资产做一个多图转场」
                            // 会被误识别为视频。
                            // 显式切链短语（我要分镜/改成图片/切到.../审查分镜等）先走 RouteChangeService 判定。
                            if ("custom".equals(submitAction) && !customText.isBlank()) {
                                if (routeChangeService.shouldSwitch(customText)) {
                                    AgentTraceAccumulator traceForRoute = traceByThread.get(state.<AgentConversation>value(K_CONVERSATION).orElseThrow().getId());
                                    if (traceForRoute != null) {
                                        traceForRoute.add("route_change", "moon", "切链", "命中显式切链短语: " + customText, intent, routeChangeService.detectTargetIntent(customText), "route_changed");
                                    }
                                    return "switch_to_run";
                                }
                                log.info("PlanGraph: resume 自定义输入未命中显式切链短语，留在当前链 cpAction={} text={}", cpAction, customText);
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
                            return routeTarget(intent);
                        }),
                        allNodeTargets());

        // handler 适配器节点：通过 SpecialistAgent 执行（有 specialist 用 specialist，否则自动包装 handler）
        for (IntentHandler handler : intentHandlers) {
            String intent = handler.intentType();
            String nodeName = "node_" + intent.replace("intent-", "");
            graph.addNode(nodeName, node_async(state -> {
                // Phase 3: 通过 SpecialistAgentRegistry 获取 specialist（自动包装 handler）
                SpecialistAgent specialist = specialistRegistry.get(intent).orElse(null);
                if (specialist == null) {
                    log.warn("PlanGraph: 无 specialist 覆盖 {}", intent);
                    // 降级：直接调 handler（保持兼容）
                    OrchestrationRequest request = stateRequest(state);
                    request.setIntentType(intent);
                    if (Boolean.TRUE.equals(state.value(K_RESUME).orElse(false))) {
                        request.setAction(state.<String>value(K_SUBMIT_ACTION).orElse(""));
                        request.setCustomText(state.<String>value(K_CUSTOM_TEXT).orElse(""));
                        request.setParams(state.<Map<String, String>>value(K_PARAMS).orElse(Map.of()));
                        request.setAssetIds(state.<List<String>>value(K_ASSET_IDS).orElse(List.of()));
                        AgentCheckpoint cp = new AgentCheckpoint();
                        cp.setAction(state.<String>value(K_CP_ACTION).orElse(""));
                        cp.setPlan(state.<String>value(K_CP_PLAN).orElse(""));
                        return Map.of(K_LAST_MESSAGE, handler.resume(request, cp));
                    }
                    return Map.of(K_LAST_MESSAGE, handler.handle(request));
                }

                // 构建 SpecialistContext
                AgentSkill skill = skillManager.byIntent(intent).orElse(null);
                SpecialistContext ctx = new SpecialistContext(
                        state.<AgentConversation>value(K_CONVERSATION).orElseThrow(),
                        state.<String>value(K_CONTENT).orElse(""),
                        state.<java.util.List<String>>value(K_PIC_URLS).orElse(java.util.List.of()),
                        emittersByThread.get(state.<AgentConversation>value(K_CONVERSATION).orElseThrow().getId()),
                        null,  // previousResult（单步，无链式）
                        skill,
                        state.<String>value(K_SUBMIT_ACTION).orElse(""),
                        state.<String>value(K_CUSTOM_TEXT).orElse(""),
                        state.<Map<String, String>>value(K_PARAMS).orElse(Map.of()),
                        state.<java.util.List<String>>value(K_ASSET_IDS).orElse(java.util.List.of()),
                        state.<String>value(K_CP_ACTION).orElse(""),
                        state.<String>value(K_CP_PLAN).orElse(""),
                        traceByThread.get(state.<AgentConversation>value(K_CONVERSATION).orElseThrow().getId())
                );
                SpecialistResult result = specialist.execute(ctx);
                if (result.needsHandoff()) {
                    log.info("PlanGraph: {} 移交到 {} 原因={}", intent, result.handoffTarget(), result.handoffReason());
                    AgentTraceAccumulator hTrace = traceByThread.get(state.<AgentConversation>value(K_CONVERSATION).orElseThrow().getId());
                    if (hTrace != null) {
                        hTrace.add("handoff", intent, "移交", "移交至 " + result.handoffTarget(), intent, result.handoffTarget(), "route_changed");
                    }
                    // 移交语义：上一专家完成 → 交给下一专家走 handle（新环节）。清 resume 痕迹，
                    // 避免下一专家继承上游表单提交的 action/cpAction 误走 resume（如 scene-review 收到 action=replace）。
                    return Map.of(
                            K_LAST_MESSAGE, result.message() != null ? result.message() : "",
                            K_HANDOFF_TARGET, result.handoffTarget(),
                            K_HANDOFF_STATE, result.handoffState() != null ? result.handoffState() : Map.of(),
                            K_RESUME, false,
                            K_SUBMIT_ACTION, "",
                            K_CP_ACTION, "");
                }
                // 非移交：清空 handoff 目标，防止上一节点（如 aisplit）残留的 K_HANDOFF_TARGET
                // 把本节点自路由成死循环（scene-review 反复 self-loop）
                return Map.of(K_LAST_MESSAGE, result.message() != null ? result.message() : "",
                        K_HANDOFF_TARGET, "");
            }));
            // 移交检查条件边：有移交目标 → 目标 specialist 节点；否则 → END
            graph.addConditionalEdges(nodeName,
                    edge_async(state -> {
                        String target = state.<String>value(K_HANDOFF_TARGET).orElse("");
                        if (!target.isBlank()) {
                            String targetNode = intentToNode.get(target);
                            if (targetNode != null) return targetNode;
                        }
                        return "END";
                    }),
                    allNodeTargetsWithEnd());
        }

        // ---- 通用技能管理节点：处理无 handler 但已注册 skill 的意图（handler-less 能力走通用执行器） ----
        graph.addNode("skill_manager", node_async(state -> {
            boolean resume = Boolean.TRUE.equals(state.value(K_RESUME).orElse(false));
            String intent;
            String cpAction = state.<String>value(K_CP_ACTION).orElse("");
            if (resume && cpAction != null && cpAction.startsWith("skill-")) {
                intent = support.planField(state.<String>value(K_CP_PLAN).orElse(""), "intent");
            } else {
                intent = state.<String>value(K_INTENT).orElse(IntentRecognitionService.FALLBACK_TYPE);
            }
            AgentSkill skill = skillManager.byIntent(intent).orElse(null);
            if (skill == null) {
                log.warn("PlanGraph skill_manager: 意图 {} 无技能，降级友好提示", intent);
                OrchestrationRequest request = stateRequest(state);
                request.setIntentType(intent);
                return Map.of(K_LAST_MESSAGE, support.sendFriendlyError(request,
                        "未找到对应技能", "这个功能暂时帮不了你，换个说法试试。"));
            }
            SpecialistAgent specialist = specialistRegistry.get(intent).orElse(null);
            if (specialist == null) {
                log.warn("PlanGraph skill_manager: 意图 {} 无 specialist，降级友好提示", intent);
                OrchestrationRequest request = stateRequest(state);
                request.setIntentType(intent);
                return Map.of(K_LAST_MESSAGE, support.sendFriendlyError(request,
                        "未找到对应执行器", "这个功能暂时帮不了你，换个说法试试。"));
            }
            SpecialistContext ctx = new SpecialistContext(
                    state.<AgentConversation>value(K_CONVERSATION).orElseThrow(),
                    state.<String>value(K_CONTENT).orElse(""),
                    state.<java.util.List<String>>value(K_PIC_URLS).orElse(java.util.List.of()),
                    emittersByThread.get(state.<AgentConversation>value(K_CONVERSATION).orElseThrow().getId()),
                    null,
                    skill,
                    state.<String>value(K_SUBMIT_ACTION).orElse(""),
                    state.<String>value(K_CUSTOM_TEXT).orElse(""),
                    state.<Map<String, String>>value(K_PARAMS).orElse(Map.of()),
                    state.<java.util.List<String>>value(K_ASSET_IDS).orElse(java.util.List.of()),
                    state.<String>value(K_CP_ACTION).orElse(""),
                    state.<String>value(K_CP_PLAN).orElse(""),
                    traceByThread.get(state.<AgentConversation>value(K_CONVERSATION).orElseThrow().getId()));
            SpecialistResult result;
            try {
                result = specialist.execute(ctx);
            } catch (Exception e) {
                log.error("PlanGraph skill_manager: {} 执行失败: {}", intent, e.getMessage(), e);
                OrchestrationRequest request = stateRequest(state);
                request.setIntentType(intent);
                return Map.of(K_LAST_MESSAGE, support.sendFriendlyError(request, e.getMessage(),
                        "处理出了点问题，请稍后重试。"));
            }
            log.info("PlanGraph skill_manager: {} 完成", intent);
            return Map.of(K_LAST_MESSAGE, result.message() != null ? result.message() : "");
        }));
        graph.addEdge("skill_manager", END);

        compiledGraph = graph.compile();
        log.info("PlanGraph: 编译完成，节点={}", intentToNode.keySet());
    }

    /**
     * 处理一条用户消息（run 路径）：{@code content} → 意图识别 → 条件边分发 → handler.handle。
     *
     * @return 本轮最后一条 message 内容（供调用方落库 assistant 消息）
     */
    public String run(AgentConversation conversation, String content, java.util.List<String> picUrls, java.util.List<String> assetIds, SseEmitter emitter) {
        return runTurn(conversation, content, picUrls, assetIds, "", emitter).content();
    }

    public String run(AgentConversation conversation, String content, java.util.List<String> picUrls, java.util.List<String> assetIds, String routingHint, SseEmitter emitter) {
        return runTurn(conversation, content, picUrls, assetIds, routingHint, emitter).content();
    }

    public AgentTurnResult runTurn(AgentConversation conversation, String content, java.util.List<String> picUrls, java.util.List<String> assetIds, SseEmitter emitter) {
        return runTurn(conversation, content, picUrls, assetIds, "", emitter);
    }

    public AgentTurnResult runTurn(AgentConversation conversation, String content, java.util.List<String> picUrls, java.util.List<String> assetIds, String routingHint, SseEmitter emitter) {
        return runTurn(conversation, content, picUrls, assetIds, routingHint, false, emitter);
    }

    public AgentTurnResult runTurn(AgentConversation conversation, String content, java.util.List<String> picUrls, java.util.List<String> assetIds, String routingHint, boolean cancelActiveTasks, SseEmitter emitter) {
        emittersByThread.put(conversation.getId(), emitter);
        AgentTraceAccumulator trace = new AgentTraceAccumulator(
                new OrchestrationRequest(conversation, content, picUrls == null ? java.util.List.of() : picUrls, emitter));
        traceByThread.put(conversation.getId(), trace);
        try {
            if (cancelActiveTasks) {
                routeTaskCancellationService.cancelPendingTasks(conversation.getId());
                trace.add("route_change", "moon", "取消旧任务", "用户已发起新需求，取消当前异步任务", "", "", "node_completed");
            }
            RunnableConfig config = RunnableConfig.builder().threadId(conversation.getId()).build();
            // 关键：run() 是全新消息，必须重置 resume 专用状态键——graph-core 的 CompileConfig 默认
            // 注册 MemorySaver，GraphRunnerContext.initializeFromStart → getInitialState 会按
            // threadId(=conversationId) 把上一次 resume 留下的完整 OverAllState 取出来再盖上新 inputs。
            // run() 的 inputs 若不带 submitAction/cpAction 等键，它们会保留上次 resume 的 stale 值
            // （如 submitAction=cancel、cpAction=agree）→ 新消息被误判成「取消续流」→ 反复回
            // 「已取消本次分镜导入」（2026-08-28 实测）。此处显式清空全部 resume 键。
            Map<String, Object> inputs = new HashMap<>();
            inputs.put(K_CONVERSATION, conversation);
            inputs.put(K_CONTENT, content);
            inputs.put(K_PIC_URLS, picUrls == null ? java.util.List.of() : picUrls);
            inputs.put(K_ASSET_IDS, assetIds == null ? java.util.List.of() : assetIds);
            inputs.put(K_RESUME, false);
            inputs.put(K_SUBMIT_ACTION, "");
            inputs.put(K_CP_ACTION, "");
            inputs.put(K_CP_PLAN, "");
            inputs.put(K_CUSTOM_TEXT, "");
            inputs.put(K_ROUTING_HINT, routingHint == null ? "" : routingHint);
            inputs.put(K_PARAMS, Map.of());
            inputs.put(K_HANDOFF_TARGET, "");
            inputs.put(K_HANDOFF_STATE, Map.of());
            inputs.put(K_LAST_MESSAGE, "");
            Optional<OverAllState> result = compiledGraph.invoke(inputs, config);
            String lastMessage = result.flatMap(s -> s.<String>value(K_LAST_MESSAGE)).orElse("");
            return buildTurnResult(conversation, lastMessage, trace);
        } finally {
            emittersByThread.remove(conversation.getId());
            traceByThread.remove(conversation.getId());
            requestsByThread.remove(conversation.getId());
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
        return resumeTurn(conversation, checkpoint, action, customText, params, assetIds, routingHint, emitter).content();
    }

    public AgentTurnResult resumeTurn(AgentConversation conversation, AgentCheckpoint checkpoint,
                         String action, String customText, Map<String, String> params,
                         java.util.List<String> assetIds, String routingHint, SseEmitter emitter) {
        emittersByThread.put(conversation.getId(), emitter);
        AgentTraceAccumulator trace = new AgentTraceAccumulator(
                new OrchestrationRequest(conversation, "", java.util.List.of(), emitter));
        traceByThread.put(conversation.getId(), trace);
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
            // 恢复卡片上下文：参考图与资产从 checkpoint/会话快照带回来，避免「已上传资产+提示词」丢失
            inputs.put(K_PIC_URLS, support.planPicUrls(checkpoint.getPlan() == null ? "" : checkpoint.getPlan()));
            List<String> resumeAssetIds = assetIds == null || assetIds.isEmpty()
                    ? support.getSessionAssets(conversation.getId()) : assetIds;
            inputs.put(K_ASSET_IDS, resumeAssetIds);
            inputs.put(K_ROUTING_HINT, routingHint == null ? "" : routingHint);
            inputs.put(K_LAST_MESSAGE, "");
            Optional<OverAllState> result = compiledGraph.invoke(inputs, config);
            String lastMessage = result.flatMap(s -> s.<String>value(K_LAST_MESSAGE)).orElse("");
            return buildTurnResult(conversation, lastMessage, trace);
        } finally {
            emittersByThread.remove(conversation.getId());
            traceByThread.remove(conversation.getId());
            requestsByThread.remove(conversation.getId());
        }
    }

    /** 构建 AgentTurnResult：从最后一个 OrchestrationRequest 读取 metadata/reasoning/suggestedActions/hitl，从 trace 读取事件。 */
    private AgentTurnResult buildTurnResult(AgentConversation conversation, String lastMessage, AgentTraceAccumulator trace) {
        OrchestrationRequest req = requestsByThread.get(conversation.getId());
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        if (req != null) {
            metadata.putAll(req.getTurnMetadata());
            if (req.getReasoning() != null) metadata.put("reasoning", req.getReasoning());
            if (req.getSuggestedActions() != null && !req.getSuggestedActions().isEmpty()) {
                metadata.put("suggestedActions", req.getSuggestedActions());
            }
        }
        if (trace != null) {
            metadata.putAll(trace.getTurnMetadata());
            if (!metadata.containsKey("reasoning") && trace.getReasoning() != null) {
                metadata.put("reasoning", trace.getReasoning());
            }
            if (!metadata.containsKey("suggestedActions") && !trace.getSuggestedActions().isEmpty()) {
                metadata.put("suggestedActions", trace.getSuggestedActions());
            }
        }
        AgentReasoningSnapshot reasoning = req != null ? req.getReasoning() : null;
        if (reasoning == null && trace != null) reasoning = trace.getReasoning();
        List<AgentTraceEvent> events = trace != null ? trace.snapshot() : List.of();
        List<SuggestedAction> suggestedActions = req != null && !req.getSuggestedActions().isEmpty()
                ? req.getSuggestedActions() : trace != null ? trace.getSuggestedActions() : List.of();
        boolean hitlPaused = (req != null && req.isHitlPaused()) || (trace != null && trace.isHitlPaused());
        String messageId = req != null && !req.getHitlMessageId().isBlank()
                ? req.getHitlMessageId() : trace != null ? trace.getHitlMessageId() : "";
        return new AgentTurnResult(lastMessage == null ? "" : lastMessage, metadata, events,
                reasoning, suggestedActions, hitlPaused, messageId);
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

    /** run 路由目标：有 handler → 原节点；无 handler 但已注册 skill → skill_manager；否则 → intent-other */
    private String routeTarget(String intent) {
        String node = intentToNode.get(intent);
        if (node != null) return node;
        if (intent != null && skillManager.byIntent(intent).isPresent()) {
            return "skill_manager";
        }
        return intentToNode.get(IntentRecognitionService.FALLBACK_TYPE);
    }

    /** state → OrchestrationRequest（handler 适配器用） */
    private OrchestrationRequest stateRequest(OverAllState state) {
        AgentConversation conversation = state.<AgentConversation>value(K_CONVERSATION).orElseThrow();
        String content = state.<String>value(K_CONTENT).orElse("");
        java.util.List<String> picUrls = state.<java.util.List<String>>value(K_PIC_URLS).orElse(java.util.List.of());
        SseEmitter emitter = emittersByThread.get(conversation.getId());
        if (emitter == null) {
            throw new IllegalStateException("PlanGraph: emitter 未注册 conversationId=" + conversation.getId());
        }
        OrchestrationRequest request = new OrchestrationRequest(conversation, content, picUrls == null || picUrls.isEmpty() ? java.util.List.of() : picUrls, emitter);
        AgentTraceAccumulator trace = traceByThread.get(conversation.getId());
        if (trace != null) request.setTrace(trace);
        request.setRoutingHint(state.<String>value(K_ROUTING_HINT).orElse(""));
        requestsByThread.put(conversation.getId(), request);
        return request;
    }

    /** 条件边输出 → 节点映射（run 路由：intent_clarify + 各 handler 节点） */
    private Map<String, String> allNodeTargets() {
        Map<String, String> m = new HashMap<>();
        m.put("intent_clarify", "intent_clarify");
        m.put("skill_manager", "skill_manager");
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

