package com.moon.moonagent.ai.agent.orchestration;

import com.moon.moonagent.orchestration.CapabilityDescriptor;
import com.moon.moonagent.orchestration.EvaluationMode;
import com.moon.moonagent.orchestration.HitlPolicy;
import com.moon.moonagent.orchestration.TaskNode;
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
        TaskPlan first;
        try {
            first = fromDraft(request,
                    synthesizer.synthesize(request, capabilities, List.of()), capabilities);
        } catch (RuntimeException e) {
            return fallback(request, capabilities, List.of("synthesis failed: " + e.getMessage()));
        }
        TaskPlanValidationResult firstResult = validator.validate(first, capabilities);
        if (firstResult.valid()) {
            return first;
        }

        TaskPlan repaired;
        try {
            repaired = fromDraft(request,
                    synthesizer.synthesize(request, capabilities, firstResult.errors()), capabilities);
        } catch (RuntimeException e) {
            return fallback(request, capabilities, List.of("synthesis failed: " + e.getMessage()));
        }
        TaskPlanValidationResult repairedResult = validator.validate(repaired, capabilities);
        if (repairedResult.valid()) {
            return repaired;
        }

        return fallback(request, capabilities, repairedResult.errors());
    }

    private static TaskPlan fromDraft(TaskPlanningRequest request, TaskPlanDraft draft,
                                      Map<String, CapabilityDescriptor> capabilities) {
        List<TaskNode> nodes = draft.nodes().stream()
                .map(node -> mergeDefaults(node, capabilities.get(node.capabilityKey())))
                .toList();
        int maxAttempts = request.maxAttemptsPerNode();
        for (TaskNode node : nodes) {
            CapabilityDescriptor descriptor = capabilities.get(node.capabilityKey());
            if (descriptor != null) maxAttempts = Math.min(maxAttempts, descriptor.maxAttempts());
        }
        TaskPlanState state = nodes.stream().anyMatch(node ->
                node.hitlPolicy() == HitlPolicy.BEFORE || node.hitlPolicy() == HitlPolicy.MANUAL)
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
                draft.edges(),
                "",
                "");
    }

    private static TaskNode mergeDefaults(TaskNode node, CapabilityDescriptor descriptor) {
        if (descriptor == null) return node;
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
                HitlPolicy.BEFORE,
                EvaluationMode.HYBRID,
                "",
                "",
                "",
                "");
        return new TaskPlan(
                UUID.randomUUID().toString(),
                request.conversationId(),
                request.goal(),
                TaskPlanState.WAITING_HUMAN,
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
