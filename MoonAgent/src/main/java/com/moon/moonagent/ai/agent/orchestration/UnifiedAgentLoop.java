package com.moon.moonagent.ai.agent.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.AgentReasoningSnapshot;
import com.moon.moonagent.ai.agent.AgentTraceEvent;
import com.moon.moonagent.ai.agent.AgentTurnResult;
import com.moon.moonagent.ai.agent.CanvasSessionContext;
import com.moon.moonagent.ai.agent.IntentRecognitionService;
import com.moon.moonagent.ai.agent.IntentResult;
import com.moon.moonagent.ai.agent.SuggestedAction;
import com.moon.moonagent.ai.agent.canvas.CanvasModeIntentPolicy;
import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
import com.moon.moonagent.ai.agent.handler.AgentTraceAccumulator;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.agent.skill.AgentSkill;
import com.moon.moonagent.ai.agent.skill.SkillManager;
import com.moon.moonagent.ai.agent.specialist.SpecialistAgent;
import com.moon.moonagent.ai.agent.specialist.SpecialistAgentRegistry;
import com.moon.moonagent.ai.agent.specialist.SpecialistContext;
import com.moon.moonagent.ai.agent.specialist.SpecialistResult;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentLoopRun;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.mapper.AgentMessageMapper;
import com.moon.moonagent.orchestration.ChainHandoff;
import com.moon.moonagent.orchestration.ChainType;
import com.moon.moonagent.orchestration.OrchestrationMode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * UnifiedLoop 首版：统一决策、切链、Trace 和恢复入口，业务步骤仍委托现有
 * SpecialistAgent / IntentHandler。旧 PlanGraph 只由 LEGACY 会话或旧 checkpoint 使用。
 */
@Component
@RequiredArgsConstructor
public class UnifiedAgentLoop {

    private static final Logger log = LoggerFactory.getLogger(UnifiedAgentLoop.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_STEPS = 6;

    private final IntentRecognitionService intentRecognitionService;
    private final AgentMessageMapper messageMapper;
    private final AgentOrchestratorSupport support;
    private final SkillManager skillManager;
    private final SpecialistAgentRegistry specialistRegistry;
    private final OrchestrationRunRecorder recorder;
    private final AigcGatewaySpecialist aigcGatewaySpecialist;

    /** 默认关闭：AIGC 必须先走现有 Skill 的理解、方案、参数和 HITL，再决定最终执行器。 */
    @Value("${ai.agent.unified-media-gateway-enabled:false}")
    private boolean unifiedMediaGatewayEnabled;

    @Autowired(required = false)
    private transient DynamicTaskEngine dynamicTaskEngine;

    @Value("${ai.agent.dynamic-plan.enabled:true}")
    private boolean dynamicPlanEnabled;

    public AgentTurnResult runTurn(AgentConversation conversation, String content,
                                   List<String> picUrls, List<String> assetIds,
                                   String routingHint, boolean cancelActiveTasks,
                                   CanvasSessionContext canvasContext,
                                   List<String> referenceVideos, List<String> referenceAudios,
                                   SseEmitter emitter) {
        OrchestrationRequest request = new OrchestrationRequest(conversation, content, picUrls, emitter);
        request.setRoutingHint(routingHint);
        request.setCanvasContext(canvasContext);
        request.setAssetIds(assetIds == null ? List.of() : assetIds);
        request.setReferenceVideos(referenceVideos == null ? List.of() : referenceVideos);
        request.setReferenceAudios(referenceAudios == null ? List.of() : referenceAudios);
        AgentTraceAccumulator trace = new AgentTraceAccumulator(request);
        trace.setTurnMetadata(runtimeMetadata());
        request.setTrace(trace);

        String intent = resolveIntent(conversation, content, routingHint, canvasContext, trace);
        ChainType chain = resolveChain(intent, canvasContext);
        AgentLoopRun run = recorder.start(conversation.getId(), "", OrchestrationMode.UNIFIED_LOOP,
                chain == null ? "" : chain.name(), Map.of("intent", intent));
        try {
            if (dynamicPlanEnabled && dynamicTaskEngine != null && canvasContext == null) {
                AgentTurnResult turn = dynamicTaskEngine.runTurn(
                        conversation, content, intent, request, trace, run);
                recorder.finish(run, turn.hitlPaused() ? "waiting_user" : "completed",
                        "FINISH", chain == null ? "" : chain.name(),
                        Map.of("intent", intent, "dynamicPlan", true), "", "");
                return withoutOwnedMetadata(turn, run);
            }
            SpecialistResult result = execute(conversation, intent, request, trace, run, 0);
            AgentTurnResult turn = buildTurnResult(request, trace, result == null ? "" : result.message());
            recorder.finish(run, turn.hitlPaused() ? "waiting_user" : "completed",
                    "FINISH", chain == null ? "" : chain.name(),
                    Map.of("intent", intent), "", "");
            return withoutOwnedMetadata(turn, run);
        } catch (Exception e) {
            recorder.finish(run, "failed", "FAIL", chain == null ? "" : chain.name(),
                    Map.of("intent", intent), "", e.getMessage());
            throw e;
        }
    }

    public AgentTurnResult resumeTurn(AgentConversation conversation, AgentCheckpoint checkpoint,
                                      String action, String customText, Map<String, String> params,
                                      List<String> assetIds, String routingHint,
                                      CanvasSessionContext canvasContext, SseEmitter emitter) {
        OrchestrationRequest request = new OrchestrationRequest(conversation,
                support.planField(checkpoint.getPlan(), "content"), support.planPicUrls(checkpoint.getPlan()), emitter);
        request.setAction(action == null ? "" : action);
        request.setCustomText(customText == null ? "" : customText);
        request.setParams(params == null ? Map.of() : params);
        request.setAssetIds(assetIds == null ? List.of() : assetIds);
        request.setCheckpointAction(checkpoint.getAction());
        request.setCheckpointPlan(checkpoint.getPlan());
        request.setRoutingHint(routingHint);
        request.setCanvasContext(canvasContext);
        AgentTraceAccumulator trace = new AgentTraceAccumulator(request);
        trace.setTurnMetadata(runtimeMetadata());
        request.setTrace(trace);

        String intent = resolveCheckpointIntent(checkpoint, canvasContext);
        ChainType chain = resolveChain(intent, canvasContext);
        AgentLoopRun run = recorder.start(conversation.getId(), "", OrchestrationMode.UNIFIED_LOOP,
                chain == null ? "" : chain.name(), Map.of("intent", intent, "resume", true));
        try {
            boolean planOwned = checkpoint.getPlanId() != null && !checkpoint.getPlanId().isBlank();
            if (dynamicPlanEnabled && dynamicTaskEngine != null && canvasContext == null
                    && (planOwned || dynamicTaskEngine.hasActivePlan(conversation.getId()))) {
                AgentTurnResult turn = dynamicTaskEngine.resumeTurn(
                        conversation, checkpoint, intent, request, trace, run);
                recorder.finish(run, turn.hitlPaused() ? "waiting_user" : "completed",
                        "FINISH", chain == null ? "" : chain.name(),
                        Map.of("intent", intent, "dynamicPlan", true, "resume", true),
                        checkpoint.getId(), "");
                return withoutOwnedMetadata(turn, run);
            }
            SpecialistResult result = execute(conversation, intent, request, trace, run, 0);
            AgentTurnResult turn = buildTurnResult(request, trace, result == null ? "" : result.message());
            recorder.finish(run, turn.hitlPaused() ? "waiting_user" : "completed",
                    "FINISH", chain == null ? "" : chain.name(),
                    Map.of("intent", intent, "resume", true), checkpoint.getId(), "");
            return withoutOwnedMetadata(turn, run);
        } catch (Exception e) {
            recorder.finish(run, "failed", "FAIL", chain == null ? "" : chain.name(),
                    Map.of("intent", intent, "resume", true), checkpoint.getId(), e.getMessage());
            throw e;
        }
    }

    /** Shadow 只记录新 Loop 的首个决策，不执行 Specialist、不写媒体。 */
    public AgentLoopRun preview(AgentConversation conversation, String content, String routingHint,
                                CanvasSessionContext canvasContext) {
        String intent = resolveIntent(conversation, content, routingHint, canvasContext, null);
        ChainType chain = resolveChain(intent, canvasContext);
        AgentLoopRun run = recorder.start(conversation.getId(), "", OrchestrationMode.SHADOW,
                chain == null ? "" : chain.name(), Map.of("intent", intent, "shadow", true));
        recorder.step(run, 0, chain == null ? "" : chain.name(), "shadow_decision", "completed",
                "FINISH", "", "shadow decision only", "", "", "",
                OffsetDateTime.now(), OffsetDateTime.now());
        recorder.finish(run, "completed", "FINISH", chain == null ? "" : chain.name(),
                Map.of("intent", intent, "shadow", true), "", "");
        return run;
    }

    private SpecialistResult execute(AgentConversation conversation, String initialIntent,
                                     OrchestrationRequest request, AgentTraceAccumulator trace,
                                     AgentLoopRun run, int startIndex) {
        String intent = initialIntent == null || initialIntent.isBlank()
                ? IntentRecognitionService.FALLBACK_TYPE : initialIntent;
        SpecialistResult result = null;
        for (int i = 0; i < MAX_STEPS; i++) {
            support.checkInterrupted(request);
            SpecialistAgent specialist = specialistRegistry.get(intent).orElse(null);
            if (specialist == null) {
                recorder.step(run, startIndex + i, chainName(intent, request.getCanvasContext()),
                        "resolve_specialist", "failed", "FAIL", intent, "no specialist", "", "", intent,
                        OffsetDateTime.now(), OffsetDateTime.now());
                return SpecialistResult.done("这个功能暂时不可用：" + intent);
            }
            AgentSkill skill = skillManager.byIntent(intent).orElse(null);
            OffsetDateTime started = OffsetDateTime.now();
            request.setIntentType(intent);
            SpecialistContext context = new SpecialistContext(
                    conversation, request.getContent(), request.getPicUrls(), request.getEmitter(),
                    result, skill, request.getAction(), request.getCustomText(), request.getParams(),
                    request.getAssetIds(), request.getCheckpointAction(), request.getCheckpointPlan(),
                    trace, request.getCanvasContext());
            boolean gatewayMedia = unifiedMediaGatewayEnabled
                    && ("intent-pic".equals(intent) || "intent-video".equals(intent));
            result = gatewayMedia
                    ? aigcGatewaySpecialist.execute(intent, context)
                    : specialist.execute(context);
            recorder.step(run, startIndex + i, chainName(intent, request.getCanvasContext()),
                    "specialist", result.needsHandoff() ? "handoff" : "completed",
                    result.needsHandoff() ? "SWITCH_CHAIN" : "FINISH",
                    intent, result.message(), "", "", "", started, OffsetDateTime.now());
            if (!result.needsHandoff()) {
                return result;
            }
            ChainHandoff handoff = new ChainHandoff(UUID.randomUUID().toString(), run.getId(),
                    resolveChain(intent, request.getCanvasContext()),
                    resolveChain(result.handoffTarget(), request.getCanvasContext()),
                    result.handoffReason(), result.handoffState());
            recorder.handoff(run, handoff);
            intent = result.handoffTarget();
        }
        throw new IllegalStateException("UnifiedLoop exceeded max handoff steps");
    }

    private String resolveIntent(AgentConversation conversation, String content, String routingHint,
                                 CanvasSessionContext canvasContext, AgentTraceAccumulator trace) {
        if (canvasContext != null && canvasContext.isCanvasEditMode()) {
            String matched = content == null || content.isBlank() ? null : intentRecognitionService.ruleMatch(content);
            return CanvasModeIntentPolicy.resolve(content, matched);
        }
        if (routingHint != null && !routingHint.isBlank()) return routingHint;
        List<AgentMessage> recent = messageMapper.selectList(
                new LambdaQueryWrapper<AgentMessage>()
                        .eq(AgentMessage::getConversationId, conversation.getId())
                        .orderByDesc(AgentMessage::getCreatedAt)
                        .last("LIMIT " + IntentRecognitionService.HISTORY_LIMIT)).reversed();
        IntentResult recognized = intentRecognitionService.recognize(content, recent);
        if (trace != null) {
            trace.add("intent_recognize", "unified-loop", "意图识别",
                    "识别为 " + recognized.type(), "", recognized.type(), "node_completed");
        }
        return recognized.type();
    }

    private static String resolveCheckpointIntent(AgentCheckpoint checkpoint, CanvasSessionContext canvasContext) {
        String action = checkpoint == null ? "" : checkpoint.getAction();
        if (canvasContext != null && canvasContext.isCanvasEditMode()) {
            return CanvasModeIntentPolicy.DESIGN;
        }
        String plannedIntent = plannedIntent(checkpoint == null ? null : checkpoint.getPlan());
        if (!plannedIntent.isBlank()) return plannedIntent;
        if (action == null) return IntentRecognitionService.FALLBACK_TYPE;
        return switch (action) {
            case "clarify-option", "scene-mode", "requirement-clarify", "scene-regenerate",
                    "scene-select", "script-source", "expand-review", "agree", "disagree",
                    "replace", "append", "cancel" -> "intent-aisplit";
            case "generate_image", "pic-option", "pic-clarify", "prompt-source", "refine" -> "intent-pic";
            case "generate_video", "video-opinion", "video-clarify", "video-source", "video-role-select" -> "intent-video";
            case "delete-confirm" -> "intent-delete";
            case "review-action" -> "intent-scene-review";
            default -> action.startsWith("skill-") ? "intent-test" : IntentRecognitionService.FALLBACK_TYPE;
        };
    }

    private static String plannedIntent(String plan) {
        if (plan == null || plan.isBlank()) return "";
        try {
            JsonNode root = JSON.readTree(plan);
            String direct = root.path("intent").asText("");
            if (!direct.isBlank()) return direct;
            JsonNode items = root.path("items");
            if (items.isArray() && !items.isEmpty()) {
                return items.get(0).path("intent").asText("");
            }
        } catch (Exception ignored) {
            // Fall back to action-based compatibility routing.
        }
        return "";
    }

    private static ChainType resolveChain(String intent, CanvasSessionContext canvasContext) {
        if (canvasContext != null && canvasContext.isCanvasEditMode()) return ChainType.CANVAS;
        if (intent == null || intent.isBlank()) return null;
        if (intent.startsWith("intent-canvas")) return ChainType.CANVAS;
        return switch (intent) {
            case "intent-pic", "intent-video" -> ChainType.AIGC;
            case "intent-aisplit", "intent-scene-review", "intent-delete" -> ChainType.STORYBOARD;
            default -> null;
        };
    }

    private static String chainName(String intent, CanvasSessionContext canvasContext) {
        ChainType chain = resolveChain(intent, canvasContext);
        return chain == null ? "" : chain.name();
    }

    private static AgentTurnResult buildTurnResult(OrchestrationRequest request,
                                                   AgentTraceAccumulator trace,
                                                   String lastMessage) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (request != null) metadata.putAll(request.getTurnMetadata());
        if (trace != null) metadata.putAll(trace.getTurnMetadata());
        AgentReasoningSnapshot reasoning = request != null ? request.getReasoning() : null;
        if (reasoning == null && trace != null) reasoning = trace.getReasoning();
        if (reasoning != null) metadata.put("reasoning", reasoning);
        List<AgentTraceEvent> events = trace == null ? List.of() : trace.snapshot();
        List<SuggestedAction> suggested = request != null && !request.getSuggestedActions().isEmpty()
                ? request.getSuggestedActions()
                : trace == null ? List.of() : trace.getSuggestedActions();
        if (!suggested.isEmpty()) metadata.put("suggestedActions", suggested);
        boolean hitl = (request != null && request.isHitlPaused()) || (trace != null && trace.isHitlPaused());
        String messageId = request != null && !request.getHitlMessageId().isBlank()
                ? request.getHitlMessageId()
                : trace == null ? "" : trace.getHitlMessageId();
        return new AgentTurnResult(lastMessage == null ? "" : lastMessage, metadata, events,
                reasoning, suggested, hitl, messageId);
    }

    private static AgentTurnResult withoutOwnedMetadata(AgentTurnResult result, AgentLoopRun run) {
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
        metadata.put("orchestrationMode", "UNIFIED_LOOP");
        metadata.put("engineVersion", run.getEngineVersion());
        metadata.put("loopRunId", run.getId());
        metadata.put("traceId", run.getTraceId());
        return new AgentTurnResult(result.content(), metadata, result.agentTrace(), result.reasoning(),
                result.suggestedActions(), result.hitlPaused(), result.messageId());
    }

    private static Map<String, Object> runtimeMetadata() {
        return Map.of(
                "orchestrationMode", "UNIFIED_LOOP",
                "engineVersion", OrchestrationMode.UNIFIED_LOOP.engineVersion(),
                "checkpointOwner", "UNIFIED_LOOP");
    }
}
