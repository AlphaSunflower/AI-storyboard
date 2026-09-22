package com.moon.moonagent.ai.agent.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.AgentReasoningSnapshot;
import com.moon.moonagent.ai.agent.AgentTraceEvent;
import com.moon.moonagent.ai.agent.AgentTurnResult;
import com.moon.moonagent.ai.agent.ConversationLock;
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
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class DynamicTaskEngine {

    private static final Logger log = LoggerFactory.getLogger(DynamicTaskEngine.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern IMAGE_MARKDOWN = Pattern.compile("!\\[[^]]*]\\(([^)]+)\\)");

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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private transient ConversationLock conversationLock;

    public AgentTurnResult runTurn(AgentConversation conversation, String content,
                                   String preferredCapability,
                                   OrchestrationRequest request,
                                   AgentTraceAccumulator trace,
                                   AgentLoopRun run) {
        TaskPlan waiting = planStore.findActive(conversation.getId()).orElse(null);
        if (waiting != null) {
            TaskNode node = waiting.nodes().stream()
                    .filter(candidate -> candidate.state() == TaskNodeState.WAITING_HUMAN)
                    .findFirst()
                    .orElse(null);
            if (node != null && !shouldReplanForNewIntent(node, preferredCapability, content)) {
                prepareQuestionResume(request, node, content);
                return continuePlan(conversation, request, trace, run, waiting, node.nodeId());
            }
            if (node != null) {
                boolean carried = carryGeneratedArtifacts(waiting, request);
                planStore.update(withState(waiting, TaskPlanState.CANCELLED,
                        "superseded by user intent " + preferredCapability));
                return startNewPlan(conversation, artifactAwareGoal(content, waiting, carried),
                        preferredCapability, request, trace, run);
            }
        }

        if ((request.getPicUrls() == null || request.getPicUrls().isEmpty())
                && looksLikeArtifactFollowUp(content)) {
            TaskPlan latest = planStore.findLatest(conversation.getId()).orElse(null);
            if (latest != null && carryGeneratedArtifacts(latest, request)) {
                return startNewPlan(conversation, artifactAwareGoal(content, latest, true),
                        preferredCapability, request, trace, run);
            }
        }
        return startNewPlan(conversation, content, preferredCapability, request, trace, run);
    }

    private AgentTurnResult startNewPlan(AgentConversation conversation, String goal,
                                         String preferredCapability,
                                         OrchestrationRequest request,
                                         AgentTraceAccumulator trace,
                                         AgentLoopRun run) {
        TaskPlan plan = planner.plan(new TaskPlanningRequest(
                        conversation.getId(), goal, preferredCapability, 20, 3, 2),
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
        boolean acquired = conversationLock == null || conversationLock.tryAcquire(conversation.getId());
        if (!acquired) return;
        try {
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
        } finally {
            if (conversationLock != null) conversationLock.release(conversation.getId());
        }
    }

    public boolean hasActivePlan(String conversationId) {
        return planStore.findActive(conversationId).isPresent();
    }

    public void resumePlan(AgentConversation conversation, TaskPlan plan) {
        boolean acquired = conversationLock == null || conversationLock.tryAcquire(conversation.getId());
        if (!acquired) return;
        try {
            OrchestrationRequest request = new OrchestrationRequest(conversation, plan.goal(), List.of(), null);
            AgentTraceAccumulator trace = new AgentTraceAccumulator(request);
            continuePlan(conversation, request, trace, null, plan);
        } finally {
            if (conversationLock != null) conversationLock.release(conversation.getId());
        }
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
        SpecialistResult previous = previousResult(plan);
        String lastMessage = previous == null || previous.message() == null ? "" : previous.message();
        int guard = Math.max(1, plan.maxNodes() * plan.maxAttemptsPerNode());
        for (int step = 0; step < guard; step++) {
            support.checkInterrupted(request);
            boolean resumingNode = !resumeNodeId.isBlank();
            TaskPlan latest = planStore.find(plan.planId()).orElse(plan);
            if (latest.state() == TaskPlanState.CANCELLED) {
                return buildTurnResult(request, trace, "任务已取消");
            }
            plan = latest;
            if (terminal(plan.state()) && !resumingNode) {
                return buildTurnResult(request, trace, lastMessage);
            }
            plan = scheduler.advance(plan);
            plan = planStore.update(plan);
            if (terminal(plan.state()) && !resumingNode) {
                return buildTurnResult(request, trace, lastMessage);
            }

            TaskNode node;
            boolean nodeResumed = resumingNode;
            if (nodeResumed) {
                String pendingNodeId = resumeNodeId;
                node = plan.nodes().stream()
                        .filter(candidate -> candidate.nodeId().equals(pendingNodeId))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("resume node not found: " + pendingNodeId));
                resumeNodeId = "";
            } else {
                node = scheduler.nextReady(plan).orElse(null);
            }
            if (!nodeResumed) {
                request.setAction("");
                request.setCustomText("");
                request.setCheckpointAction("");
                request.setCheckpointPlan("");
            }
            if (node == null) {
                TaskPlan failed = withState(plan, TaskPlanState.FAILED, "no ready task node");
                planStore.update(failed);
                return buildTurnResult(request, trace, "任务计划无法继续执行");
            }

            String transition = transitionMessage(node, plan);
            if (!transition.isBlank()) {
                support.sendWorkflowEvent(request, node.nodeId(), transition, "node_started");
                support.sendMessage(request, transition + "\n\n");
                lastMessage = transition;
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
                    conversation, nodeContent(node, request, plan),
                    nodePicUrls(node, request, plan), request.getEmitter(),
                    previous, skill, request.getAction(), request.getCustomText(), request.getParams(),
                    request.getAssetIds(), request.getCheckpointAction(), request.getCheckpointPlan(),
                    trace, request.getCanvasContext(), true);
            SpecialistResult result = executeCapability(capability, context);
            String resultMessage = result == null || result.message() == null ? "" : result.message();
            lastMessage = transition.isBlank() || resultMessage.isBlank()
                    ? transition + resultMessage
                    : transition + "\n\n" + resultMessage;
            previous = result;
            boolean failed = result != null && result.isFailed();
            recorder.step(run, step,
                    node.capabilityKey(),
                    "task_node",
                    failed ? "failed" : result != null && result.needsHandoff() ? "handoff" : "completed",
                    failed ? "FAIL" : result != null && result.needsHandoff() ? "SWITCH_CHAIN" : "CONTINUE",
                    plan.planId(), node.nodeId(), node.attemptCount() + 1,
                    "", lastMessage, "", "", "",
                    started, OffsetDateTime.now());

            if (failed) {
                plan = scheduler.failNodeTerminal(plan, node.nodeId(), lastMessage);
                planStore.update(plan);
                support.sendWorkflowEvent(request, node.nodeId(), lastMessage, "node_failed");
                return buildTurnResult(request, trace, lastMessage);
            }

            if (result != null && result.needsHandoff()) {
                request.applyHandoffState(result.handoffState());
            }

            if (result != null && result.needsUserInput()) {
                plan = scheduler.markWaitingHuman(plan, node.nodeId(), questionState(result));
                planStore.update(plan);
                support.sendWorkflowEvent(request, node.nodeId(), node.title(), "node_finished");
                return buildTurnResult(request, trace, lastMessage);
            }

            AgentCheckpoint pending = findPendingCheckpoint(
                    conversation.getId(), plan.planId(), node.nodeId(), started);
            if (pending != null) {
                pending.setPlanId(plan.planId());
                pending.setNodeId(node.nodeId());
                checkpointMapper.updateById(pending);
                plan = scheduler.markWaitingHuman(plan, node.nodeId(), lastMessage);
                planStore.update(plan);
                support.sendWorkflowEvent(request, node.nodeId(), node.title(), "node_finished");
                return buildTurnResult(request, trace, lastMessage);
            }

            AgentAsset asyncAsset = findNewAsyncAsset(
                    conversation.getId(), plan.planId(), node.nodeId(), started);
            if (asyncAsset != null) {
                asyncAsset.setPlanId(plan.planId());
                asyncAsset.setNodeId(node.nodeId());
                assetMapper.updateById(asyncAsset);
                plan = scheduler.markWaitingAsync(
                        plan, node.nodeId(), asyncAsset.getTaskId(), lastMessage);
                planStore.update(plan);
                support.sendWorkflowEvent(request, node.nodeId(), node.title(), "node_finished");
                return buildTurnResult(request, trace, lastMessage);
            }

            plan = scheduler.completeNode(plan, node.nodeId(), lastMessage);
            plan = planStore.update(plan);
            support.sendWorkflowEvent(request, node.nodeId(), node.title(), "node_finished");
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

    private AgentCheckpoint findPendingCheckpoint(String conversationId, String planId,
                                                  String nodeId, OffsetDateTime started) {
        try {
            return checkpointMapper.selectOne(new LambdaQueryWrapper<AgentCheckpoint>()
                    .eq(AgentCheckpoint::getConversationId, conversationId)
                    .eq(AgentCheckpoint::getStatus, "pending")
                    .ge(AgentCheckpoint::getCreatedAt, started.minusSeconds(1))
                    .and(wrapper -> wrapper.isNull(AgentCheckpoint::getPlanId)
                            .or().eq(AgentCheckpoint::getPlanId, planId))
                    .and(wrapper -> wrapper.isNull(AgentCheckpoint::getNodeId)
                            .or().eq(AgentCheckpoint::getNodeId, nodeId))
                    .orderByDesc(AgentCheckpoint::getCreatedAt)
                    .last("LIMIT 1"));
        } catch (Exception e) {
            log.warn("task engine checkpoint lookup failed: {}", e.getMessage());
            return null;
        }
    }

    private AgentAsset findNewAsyncAsset(String conversationId, String planId,
                                         String nodeId, OffsetDateTime started) {
        try {
            return assetMapper.selectOne(new LambdaQueryWrapper<AgentAsset>()
                    .eq(AgentAsset::getConversationId, conversationId)
                    .isNotNull(AgentAsset::getTaskId)
                    .in(AgentAsset::getStatus, List.of("queued", "generating", "running"))
                    .ge(AgentAsset::getCreatedAt, started.minusSeconds(1))
                    .and(wrapper -> wrapper.isNull(AgentAsset::getPlanId)
                            .or().eq(AgentAsset::getPlanId, planId))
                    .and(wrapper -> wrapper.isNull(AgentAsset::getNodeId)
                            .or().eq(AgentAsset::getNodeId, nodeId))
                    .orderByDesc(AgentAsset::getCreatedAt)
                    .last("LIMIT 1"));
        } catch (Exception e) {
            log.warn("task engine async asset lookup failed: {}", e.getMessage());
            return null;
        }
    }

    private static String nodeContent(TaskNode node, OrchestrationRequest request, TaskPlan plan) {
        for (String key : List.of("goal", "content", "prompt")) {
            Object value = node.parameters().get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        if (request != null && request.getContent() != null && !request.getContent().isBlank()) {
            return request.getContent();
        }
        return plan.goal();
    }

    private static void prepareQuestionResume(OrchestrationRequest request, TaskNode node, String content) {
        String toolName = "intent-video".equals(node.capabilityKey()) ? "generate_video" : "generate_image";
        request.setCheckpointAction(toolName);
        request.setCheckpointPlan(node.outputSummary());
        request.setCustomText(content == null ? "" : content);
        int selected = selectedPlanIndex(content);
        if (selected > 0) {
            request.setAction(("generate_video".equals(toolName) ? "select_video:" : "select_image:") + selected);
        } else {
            request.setAction("refine");
        }
    }

    private static boolean shouldReplanForNewIntent(TaskNode node, String preferredCapability, String content) {
        if (node == null || preferredCapability == null || preferredCapability.isBlank()) return false;
        boolean mediaIntent = "intent-pic".equals(preferredCapability)
                || "intent-video".equals(preferredCapability);
        if (!mediaIntent || preferredCapability.equals(node.capabilityKey())) return false;
        return !isPlanSelection(content);
    }

    private static boolean isPlanSelection(String content) {
        if (content == null || content.isBlank()) return false;
        return content.trim().matches("(?i)^(?:方案\\s*)?[1-3](?:\\s*[-A-Z])?$");
    }

    private static boolean looksLikeArtifactFollowUp(String content) {
        if (content == null || content.isBlank()) return false;
        String text = content.trim();
        return text.matches(".*(这|该|上|当前|刚才|之前).{0,6}(图|图片|画面).*")
                || text.matches(".*(完善|修改|改图|错字|文字.{0,4}问题|重做|重新生成).{0,12}(图|图片|画面)?.*");
    }

    private static boolean carryGeneratedArtifacts(TaskPlan plan, OrchestrationRequest request) {
        if (plan == null || request == null) return false;
        List<String> urls = new ArrayList<>();
        for (TaskNode node : plan.nodes()) {
            if (node.state() == TaskNodeState.COMPLETED
                    && node.outputSummary() != null
                    && !node.outputSummary().isBlank()) {
                collectImageUrls(node.outputSummary(), urls);
            }
        }
        if (urls.isEmpty()) return false;
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("picUrls", urls.stream().distinct().toList());
        state.put("artifactSource", "generated");
        request.applyHandoffState(state);
        return true;
    }

    private static String artifactAwareGoal(String content, TaskPlan sourcePlan, boolean carried) {
        if (!carried) return content;
        boolean continueVideo = sourcePlan != null && sourcePlan.nodes().stream()
                .anyMatch(node -> "intent-video".equals(node.capabilityKey()));
        String continuation = continueVideo
                ? "用户希望先完善这张图片，确认并生成新图后，再继续原视频任务。"
                : "用户希望继续完善这张图片。";
        return "上下文：当前图片由智能体上一步调用工具生成，不是用户上传。"
                + "请保留已有图片作为编辑源。" + continuation
                + "用户本轮要求：" + content;
    }

    private static int selectedPlanIndex(String content) {
        if (content == null || content.isBlank()) return -1;
        java.util.regex.Matcher matcher = Pattern.compile("(?:方案\\s*)?([1-3])").matcher(content.trim());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static String questionState(SpecialistResult result) {
        try {
            return JSON.writeValueAsString(result.handoffState());
        } catch (Exception e) {
            return "{}";
        }
    }

    private static List<String> nodePicUrls(TaskNode node, OrchestrationRequest request, TaskPlan plan) {
        List<String> urls = new ArrayList<>(request.getPicUrls() == null ? List.of() : request.getPicUrls());
        if (!"intent-video".equals(node.capabilityKey()) || node.dependencies().isEmpty()) {
            return urls;
        }
        for (String dependencyId : node.dependencies()) {
            TaskNode dependency = plan.nodes().stream()
                    .filter(candidate -> candidate.nodeId().equals(dependencyId))
                    .findFirst()
                    .orElse(null);
            if (dependency == null || dependency.outputSummary() == null
                    || dependency.outputSummary().isBlank()) {
                continue;
            }
            collectImageUrls(dependency.outputSummary(), urls);
        }
        return urls.stream().distinct().toList();
    }

    private static void collectImageUrls(String text, List<String> urls) {
        Matcher matcher = IMAGE_MARKDOWN.matcher(text);
        while (matcher.find()) {
            String url = matcher.group(1);
            if (!url.isBlank() && !urls.contains(url)) urls.add(url);
        }
    }

    private static String transitionMessage(TaskNode node, TaskPlan plan) {
        int index = plan.nodes().indexOf(node) + 1;
        int total = plan.nodes().size();
        String prefix = "接下来执行第 " + index + "/" + total + " 步：「" + node.title() + "」。";
        if ("intent-pic".equals(node.capabilityKey())) {
            return prefix + "先确认图片方案；生成成功后，我会把成品图作为下一步视频的参考图。";
        }
        if ("intent-video".equals(node.capabilityKey())) {
            return prefix + "上一步图片已就绪，我会使用它作为参考图；先确认视频方案，再提交视频任务。";
        }
        return prefix;
    }

    private static SpecialistResult previousResult(TaskPlan plan) {
        for (int i = plan.nodes().size() - 1; i >= 0; i--) {
            TaskNode node = plan.nodes().get(i);
            if (node.outputSummary() == null || node.outputSummary().isBlank()) continue;
            if (node.state() == TaskNodeState.COMPLETED
                    || node.state() == TaskNodeState.WAITING_HUMAN
                    || node.state() == TaskNodeState.WAITING_ASYNC) {
                return SpecialistResult.done(node.outputSummary());
            }
        }
        return null;
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
