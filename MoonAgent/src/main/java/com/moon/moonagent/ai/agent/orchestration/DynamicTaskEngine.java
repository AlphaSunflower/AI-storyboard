package com.moon.moonagent.ai.agent.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.ai.agent.AgentReasoningSnapshot;
import com.moon.moonagent.ai.agent.AgentTraceEvent;
import com.moon.moonagent.ai.agent.AgentTurnResult;
import com.moon.moonagent.ai.agent.SuggestedAction;
import com.moon.moonagent.ai.agent.capability.AgentCapability;
import com.moon.moonagent.ai.agent.capability.AgentCapabilityRegistry;
import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
import com.moon.moonagent.ai.agent.handler.AgentTraceAccumulator;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.agent.skill.AgentSkill;
import com.moon.moonagent.ai.agent.specialist.SpecialistContext;
import com.moon.moonagent.ai.agent.specialist.SpecialistResult;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentLoopRun;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.mapper.AgentCheckpointMapper;
import com.moon.moonagent.orchestration.TaskNode;
import com.moon.moonagent.orchestration.TaskNodeState;
import com.moon.moonagent.orchestration.TaskPlan;
import com.moon.moonagent.orchestration.TaskPlanScheduler;
import com.moon.moonagent.orchestration.TaskPlanState;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class DynamicTaskEngine {

    private static final Logger log = LoggerFactory.getLogger(DynamicTaskEngine.class);

    private final TaskPlanPlanner planner;
    private final TaskPlanStore planStore;
    private final TaskPlanScheduler scheduler;
    private final AgentCapabilityRegistry capabilities;
    private final AgentOrchestratorSupport support;
    private final AigcGatewaySpecialist aigcGatewaySpecialist;
    private final AgentCheckpointMapper checkpointMapper;
    private final AgentAssetMapper assetMapper;
    private final OrchestrationRunRecorder recorder;

    @Value("${ai.agent.unified-media-gateway-enabled:false}")
    private boolean unifiedMediaGatewayEnabled;

    public AgentTurnResult runTurn(AgentConversation conversation, String content,
                                   String preferredCapability,
                                   OrchestrationRequest request,
                                   AgentTraceAccumulator trace,
                                   AgentLoopRun run) {
        TaskPlan plan = planner.plan(new TaskPlanningRequest(
                        conversation.getId(), content, preferredCapability, 20, 3, 2),
                capabilities.descriptors());
        plan = planStore.save(plan);
        return continuePlan(conversation, request, trace, run, plan);
    }

    public AgentTurnResult resumeTurn(AgentConversation conversation,
                                      AgentCheckpoint checkpoint,
                                      String fallbackIntent,
                                      OrchestrationRequest request,
                                      AgentTraceAccumulator trace,
                                      AgentLoopRun run) {
        TaskPlan plan = planStore.findActive(conversation.getId())
                .orElseThrow(() -> new IllegalStateException("active task plan not found"));
        String nodeId = checkpoint == null ? "" : checkpoint.getNodeId();
        if (nodeId == null || nodeId.isBlank()) {
            nodeId = plan.nodes().stream()
                    .filter(node -> node.state() == TaskNodeState.WAITING_HUMAN)
                    .map(TaskNode::nodeId)
                    .findFirst()
                    .orElse("");
        }
        if (nodeId.isBlank()) {
            return continuePlan(conversation, request, trace, run, plan);
        }
        return continuePlan(conversation, request, trace, run, plan, nodeId);
    }

    public void continueAfterAsync(AgentConversation conversation, String planId, String nodeId,
                                   boolean success, String outputSummary) {
        TaskPlan plan = planStore.find(planId).orElse(null);
        if (plan == null || plan.state() != TaskPlanState.WAITING_ASYNC) return;
        plan = success
                ? scheduler.completeNode(plan, nodeId, outputSummary)
                : scheduler.failNode(plan, nodeId, outputSummary);
        planStore.update(plan);
        if (!success) return;
        OrchestrationRequest request = new OrchestrationRequest(conversation, plan.goal(), List.of(), null);
        AgentTraceAccumulator trace = new AgentTraceAccumulator(request);
        continuePlan(conversation, request, trace, null, plan);
    }

    public boolean hasActivePlan(String conversationId) {
        return planStore.findActive(conversationId).isPresent();
    }

    private AgentTurnResult continuePlan(AgentConversation conversation,
                                         OrchestrationRequest request,
                                         AgentTraceAccumulator trace,
                                         AgentLoopRun run,
                                         TaskPlan plan) {
        return continuePlan(conversation, request, trace, run, plan, "");
    }

    private AgentTurnResult continuePlan(AgentConversation conversation,
                                         OrchestrationRequest request,
                                         AgentTraceAccumulator trace,
                                         AgentLoopRun run,
                                         TaskPlan plan,
                                         String resumeNodeId) {
        SpecialistResult previous = null;
        String lastMessage = "";
        int guard = Math.max(1, plan.maxNodes() * plan.maxAttemptsPerNode());
        for (int step = 0; step < guard; step++) {
            support.checkInterrupted(request);
            plan = scheduler.advance(plan);
            plan = planStore.update(plan);
            if (terminal(plan.state())) {
                return buildTurnResult(request, trace, lastMessage);
            }

            TaskNode node;
            if (!resumeNodeId.isBlank()) {
                String pendingNodeId = resumeNodeId;
                node = plan.nodes().stream()
                        .filter(candidate -> candidate.nodeId().equals(pendingNodeId))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("resume node not found: " + pendingNodeId));
                resumeNodeId = "";
            } else {
                node = scheduler.nextReady(plan).orElse(null);
            }
            if (node == null) {
                TaskPlan failed = withState(plan, TaskPlanState.FAILED, "no ready task node");
                planStore.update(failed);
                return buildTurnResult(request, trace, "任务计划无法继续执行");
            }

            plan = scheduler.markRunning(plan, node.nodeId());
            plan = planStore.update(plan);
            AgentCapability capability = capabilities.find(node.capabilityKey()).orElse(null);
            if (capability == null) {
                plan = scheduler.failNode(plan, node.nodeId(), "capability not found: " + node.capabilityKey());
                planStore.update(plan);
                return buildTurnResult(request, trace, "任务能力不可用：" + node.capabilityKey());
            }

            OffsetDateTime started = OffsetDateTime.now();
            request.setIntentType(node.capabilityKey());
            AgentSkill skill = capability.skill();
            SpecialistContext context = new SpecialistContext(
                    conversation, request.getContent(), request.getPicUrls(), request.getEmitter(),
                    previous, skill, request.getAction(), request.getCustomText(), request.getParams(),
                    request.getAssetIds(), request.getCheckpointAction(), request.getCheckpointPlan(),
                    trace, request.getCanvasContext());
            SpecialistResult result = executeCapability(capability, context);
            lastMessage = result == null || result.message() == null ? "" : result.message();
            previous = result;
            recorder.step(run, step,
                    node.capabilityKey(),
                    "task_node",
                    result != null && result.needsHandoff() ? "handoff" : "completed",
                    result != null && result.needsHandoff() ? "SWITCH_CHAIN" : "CONTINUE",
                    node.nodeId(), lastMessage, "", "", "",
                    started, OffsetDateTime.now());

            AgentCheckpoint pending = findPendingCheckpoint(conversation.getId());
            if (pending != null) {
                pending.setPlanId(plan.planId());
                pending.setNodeId(node.nodeId());
                checkpointMapper.updateById(pending);
                plan = scheduler.markWaitingHuman(plan, node.nodeId());
                planStore.update(plan);
                return buildTurnResult(request, trace, lastMessage);
            }

            AgentAsset asyncAsset = findNewAsyncAsset(conversation.getId());
            if (asyncAsset != null) {
                asyncAsset.setPlanId(plan.planId());
                asyncAsset.setNodeId(node.nodeId());
                assetMapper.updateById(asyncAsset);
                plan = scheduler.markWaitingAsync(plan, node.nodeId(), asyncAsset.getTaskId());
                planStore.update(plan);
                return buildTurnResult(request, trace, lastMessage);
            }

            plan = scheduler.completeNode(plan, node.nodeId(), lastMessage);
            plan = planStore.update(plan);
        }
        TaskPlan failed = withState(plan, TaskPlanState.FAILED, "task plan exceeded execution guard");
        planStore.update(failed);
        return buildTurnResult(request, trace, "任务计划执行步数超出预算");
    }

    private SpecialistResult executeCapability(AgentCapability capability, SpecialistContext context) {
        boolean gatewayMedia = unifiedMediaGatewayEnabled
                && ("intent-pic".equals(capability.key()) || "intent-video".equals(capability.key()));
        return gatewayMedia
                ? aigcGatewaySpecialist.execute(capability.key(), context)
                : capability.execute(context);
    }

    private AgentCheckpoint findPendingCheckpoint(String conversationId) {
        try {
            return checkpointMapper.selectOne(new LambdaQueryWrapper<AgentCheckpoint>()
                    .eq(AgentCheckpoint::getConversationId, conversationId)
                    .eq(AgentCheckpoint::getStatus, "pending")
                    .orderByDesc(AgentCheckpoint::getCreatedAt)
                    .last("LIMIT 1"));
        } catch (Exception e) {
            log.warn("task engine checkpoint lookup failed: {}", e.getMessage());
            return null;
        }
    }

    private AgentAsset findNewAsyncAsset(String conversationId) {
        try {
            return assetMapper.selectOne(new LambdaQueryWrapper<AgentAsset>()
                    .eq(AgentAsset::getConversationId, conversationId)
                    .isNotNull(AgentAsset::getTaskId)
                    .in(AgentAsset::getStatus, List.of("queued", "generating", "running"))
                    .isNull(AgentAsset::getPlanId)
                    .orderByDesc(AgentAsset::getCreatedAt)
                    .last("LIMIT 1"));
        } catch (Exception e) {
            log.warn("task engine async asset lookup failed: {}", e.getMessage());
            return null;
        }
    }

    private static boolean terminal(TaskPlanState state) {
        return state == TaskPlanState.COMPLETED
                || state == TaskPlanState.FAILED
                || state == TaskPlanState.CANCELLED
                || state == TaskPlanState.WAITING_HUMAN
                || state == TaskPlanState.WAITING_ASYNC;
    }

    private static TaskPlan withState(TaskPlan plan, TaskPlanState state, String error) {
        return new TaskPlan(
                plan.planId(), plan.conversationId(), plan.goal(), state, plan.version(),
                plan.maxNodes(), plan.maxAttemptsPerNode(), plan.maxReplans(), plan.replanCount(),
                plan.nodes(), plan.edges(), error, plan.planSignature());
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
}
