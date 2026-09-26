package com.moon.moonagent.ai.agent.orchestration;

import com.moon.moonagent.orchestration.CapabilityDescriptor;
import com.moon.moonagent.orchestration.EvaluationMode;
import com.moon.moonagent.orchestration.HitlPolicy;
import com.moon.moonagent.orchestration.TaskNode;
import com.moon.moonagent.orchestration.TaskEdge;
import com.moon.moonagent.orchestration.TaskNodeState;
import com.moon.moonagent.orchestration.TaskPlan;
import com.moon.moonagent.orchestration.TaskPlanDraft;
import com.moon.moonagent.orchestration.TaskPlanState;
import com.moon.moonagent.orchestration.TaskPlanValidationResult;
import com.moon.moonagent.orchestration.TaskPlanValidator;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class TaskPlanPlanner {

    private final TaskPlanSynthesizer synthesizer;
    private final TaskPlanValidator validator;

    public TaskPlanPlanner(TaskPlanSynthesizer synthesizer, TaskPlanValidator validator) {
        this.synthesizer = synthesizer;
        this.validator = validator;
    }

    public TaskPlan plan(TaskPlanningRequest request, Map<String, CapabilityDescriptor> capabilities) {
        return plan(request, capabilities, request.trace());
    }

    public TaskPlan plan(TaskPlanningRequest request, Map<String, CapabilityDescriptor> capabilities,
                         com.moon.moonagent.ai.agent.handler.AgentTraceAccumulator trace) {
        TaskPlan first;
        try {
            first = fromDraft(request, ensureMediaPipeline(
                    synthesize(request, capabilities, List.of(), trace), request, capabilities), capabilities);
        } catch (RuntimeException e) {
            TaskPlan deterministic = deterministicMediaPlan(request, capabilities);
            if (deterministic != null) return deterministic;
            return fallback(request, capabilities, List.of("synthesis failed: " + e.getMessage()));
        }
        TaskPlanValidationResult firstResult = validator.validate(first, capabilities);
        if (firstResult.valid()) {
            return first;
        }

        TaskPlan repaired;
        try {
            repaired = fromDraft(request, ensureMediaPipeline(
                    synthesize(request, capabilities, firstResult.errors(), trace),
                    request, capabilities), capabilities);
        } catch (RuntimeException e) {
            TaskPlan deterministic = deterministicMediaPlan(request, capabilities);
            if (deterministic != null) return deterministic;
            return fallback(request, capabilities, List.of("synthesis failed: " + e.getMessage()));
        }
        TaskPlanValidationResult repairedResult = validator.validate(repaired, capabilities);
        if (repairedResult.valid()) {
            return repaired;
        }

        TaskPlan deterministic = deterministicMediaPlan(request, capabilities);
        if (deterministic != null) return deterministic;
        return fallback(request, capabilities, repairedResult.errors());
    }

    private TaskPlanDraft synthesize(TaskPlanningRequest request,
                                     Map<String, CapabilityDescriptor> capabilities,
                                     List<String> errors,
                                     com.moon.moonagent.ai.agent.handler.AgentTraceAccumulator trace) {
        return trace == null
                ? synthesizer.synthesize(request, capabilities, errors)
                : synthesizer.synthesize(request, capabilities, errors, trace);
    }

    private static TaskPlan fromDraft(TaskPlanningRequest request, TaskPlanDraft draft,
                                      Map<String, CapabilityDescriptor> capabilities) {
        List<TaskNode> nodes = draft.nodes().stream()
                .filter(java.util.Objects::nonNull)
                .map(node -> mergeDefaults(node, capabilities.get(node.capabilityKey())))
                .toList();
        List<TaskEdge> edges = draft.edges().stream()
                .filter(java.util.Objects::nonNull)
                .filter(edge -> edge.fromNodeId() != null && !edge.fromNodeId().isBlank()
                        && edge.toNodeId() != null && !edge.toNodeId().isBlank())
                .toList();
        int maxAttempts = request.maxAttemptsPerNode();
        for (TaskNode node : nodes) {
            CapabilityDescriptor descriptor = capabilities.get(node.capabilityKey());
            if (descriptor != null) maxAttempts = Math.min(maxAttempts, descriptor.maxAttempts());
        }
        TaskPlanState state = nodes.stream().anyMatch(node -> node.state() == TaskNodeState.WAITING_HUMAN)
                ? TaskPlanState.WAITING_HUMAN
                : TaskPlanState.READY;
        return new TaskPlan(
                UUID.randomUUID().toString(),
                request.conversationId(),
                draft.goal() == null || draft.goal().isBlank() ? request.goal() : draft.goal(),
                state,
                1,
                request.maxNodes(),
                maxAttempts,
                request.maxReplans(),
                0,
                nodes,
                edges,
                "",
                "");
    }

    private static TaskNode mergeDefaults(TaskNode node, CapabilityDescriptor descriptor) {
        if (descriptor == null) return node;
        if (Boolean.TRUE.equals(node.parameters().get("_directExecution"))) return node;
        HitlPolicy hitlPolicy = node.hitlPolicy() == HitlPolicy.NONE
                ? descriptor.defaultHitlPolicy() : node.hitlPolicy();
        EvaluationMode evaluationMode = node.evaluationMode() == EvaluationMode.DETERMINISTIC
                ? descriptor.evaluationMode() : node.evaluationMode();
        if (hitlPolicy == node.hitlPolicy() && evaluationMode == node.evaluationMode()) return node;
        return new TaskNode(
                node.nodeId(), node.capabilityKey(), node.title(), node.parameters(),
                node.dependencies(), node.state(), node.attemptCount(), hitlPolicy,
                evaluationMode, node.qualityCriteria(), node.outputSummary(),
                node.asyncTaskId(), node.error());
    }

    private static TaskPlanDraft ensureMediaPipeline(TaskPlanDraft draft, TaskPlanningRequest request,
                                                      Map<String, CapabilityDescriptor> capabilities) {
        TaskPlanDraft deterministic = deterministicMediaDraft(request, capabilities);
        if (deterministic == null) return draft;
        boolean alreadyHasImageAndVideo = draft.nodes().stream()
                .map(TaskNode::capabilityKey)
                .collect(java.util.stream.Collectors.toSet())
                .containsAll(List.of("intent-pic", "intent-video"));
        return alreadyHasImageAndVideo ? draft : deterministic;
    }

    private static TaskPlan deterministicMediaPlan(TaskPlanningRequest request,
                                                   Map<String, CapabilityDescriptor> capabilities) {
        TaskPlanDraft draft = deterministicMediaDraft(request, capabilities);
        return draft == null ? null : fromDraft(request, draft, capabilities);
    }

    private static TaskPlanDraft deterministicMediaDraft(TaskPlanningRequest request,
                                                         Map<String, CapabilityDescriptor> capabilities) {
        if (!capabilities.containsKey("intent-pic") || !capabilities.containsKey("intent-video")) {
            return null;
        }
        String goal = request.goal() == null ? "" : request.goal();
        boolean mediaVideoGoal = (goal.contains("视频") || goal.contains("动画"))
                && (goal.contains("图") || goal.toUpperCase(java.util.Locale.ROOT).contains("PPT"));
        if (!mediaVideoGoal) return null;

        TaskNode image = new TaskNode(
                "media-image",
                "intent-pic",
                "生成 PPT 主视觉图",
                Map.of(
                        "goal", "生成科技感、未来感的 PPT 主视觉图片。最终画面是整段视频的定格页，文本必须清晰准确："
                                + goal,
                        "prompt", "生成科技感、未来感的 PPT 主视觉图片，最终定格为完整清晰的中文排版页面："
                                + goal,
                        "_directExecution", true),
                List.of(),
                TaskNodeState.PENDING,
                0,
                HitlPolicy.NONE,
                EvaluationMode.HYBRID,
                "画面具备科技感、未来感，PPT 标题与关键信息清晰可读",
                "",
                "",
                "");
        TaskNode video = new TaskNode(
                "media-video",
                "intent-video",
                "生成前导动画并引出 PPT",
                Map.of(
                        "goal", "以上一个节点生成的 PPT 主视觉图为最终定格参考，先生成一段科技感前导动画，"
                                + "再通过镜头运动、光轨、粒子或界面构建自然引出这张 PPT。不要修改 PPT 中的文字和版式。"
                                + goal,
                        "prompt", "参考上一节点生成的 PPT 主视觉图，生成前导动画视频；动画最后稳定落到该 PPT 画面，"
                                + "保持标题、文字、版式不变。",
                        "_directExecution", true),
                List.of("media-image"),
                TaskNodeState.PENDING,
                0,
                HitlPolicy.NONE,
                EvaluationMode.HYBRID,
                "前导动画自然衔接，最终画面与参考图一致，文字不变形",
                "",
                "",
                "");
        return new TaskPlanDraft(
                goal,
                List.of(image, video),
                List.of(new TaskEdge("media-image", "media-video")));
    }

    private static TaskPlan fallback(TaskPlanningRequest request,
                                     Map<String, CapabilityDescriptor> capabilities,
                                     List<String> errors) {
        String capabilityKey = capabilities.containsKey(request.preferredCapability())
                ? request.preferredCapability()
                : capabilities.keySet().stream().sorted(Comparator.naturalOrder()).findFirst().orElse("");
        TaskNode node = new TaskNode(
                "task-1",
                capabilityKey,
                capabilityKey,
                Map.of("goal", request.goal()),
                List.of(),
                TaskNodeState.PENDING,
                0,
                HitlPolicy.NONE,
                EvaluationMode.HYBRID,
                "",
                "",
                "",
                "");
        return new TaskPlan(
                UUID.randomUUID().toString(),
                request.conversationId(),
                request.goal(),
                TaskPlanState.READY,
                1,
                request.maxNodes(),
                request.maxAttemptsPerNode(),
                request.maxReplans(),
                0,
                List.of(node),
                List.of(),
                "planner fallback: " + String.join("; ", errors),
                "");
    }
}
