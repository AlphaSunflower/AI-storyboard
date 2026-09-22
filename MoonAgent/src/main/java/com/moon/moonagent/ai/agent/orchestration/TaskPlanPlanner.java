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
        TaskPlan first = fromDraft(request, synthesizer.synthesize(request, capabilities, List.of()));
        TaskPlanValidationResult firstResult = validator.validate(first, capabilities);
        if (firstResult.valid()) {
            return first;
        }

        TaskPlan repaired = fromDraft(request,
                synthesizer.synthesize(request, capabilities, firstResult.errors()));
        TaskPlanValidationResult repairedResult = validator.validate(repaired, capabilities);
        if (repairedResult.valid()) {
            return repaired;
        }

        return fallback(request, capabilities, repairedResult.errors());
    }

    private static TaskPlan fromDraft(TaskPlanningRequest request, TaskPlanDraft draft) {
        return new TaskPlan(
                UUID.randomUUID().toString(),
                request.conversationId(),
                draft.goal() == null || draft.goal().isBlank() ? request.goal() : draft.goal(),
                TaskPlanState.READY,
                1,
                request.maxNodes(),
                request.maxAttemptsPerNode(),
                request.maxReplans(),
                0,
                draft.nodes(),
                draft.edges(),
                "",
                "");
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
