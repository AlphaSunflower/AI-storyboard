package com.moon.moonagent.ai.agent.orchestration;

import com.moon.moonagent.orchestration.TaskNode;
import com.moon.moonagent.orchestration.TaskNodeState;
import com.moon.moonagent.orchestration.TaskPlan;
import com.moon.moonagent.orchestration.TaskPlanState;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class TaskPlanActionService {

    public TaskPlan apply(TaskPlan plan, String action) {
        String normalized = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "cancel" -> withState(plan, TaskPlanState.CANCELLED, plan.nodes());
            case "approve", "resume" -> withNodeState(
                    plan, TaskNodeState.WAITING_HUMAN, TaskNodeState.READY, TaskPlanState.RUNNING);
            case "reject" -> withNodeState(
                    plan, TaskNodeState.WAITING_HUMAN, TaskNodeState.FAILED, TaskPlanState.FAILED);
            case "retry" -> withNodeState(
                    plan, TaskNodeState.FAILED, TaskNodeState.READY, TaskPlanState.RUNNING);
            case "replan" -> withState(plan, TaskPlanState.REPLANNING, plan.nodes());
            default -> throw new IllegalArgumentException("unsupported plan action: " + action);
        };
    }

    private static TaskPlan withNodeState(TaskPlan plan, TaskNodeState from,
                                          TaskNodeState to, TaskPlanState planState) {
        AtomicBoolean changed = new AtomicBoolean(false);
        List<TaskNode> nodes = plan.nodes().stream()
                .map(node -> {
                    if (!changed.get() && node.state() == from) {
                        changed.set(true);
                        return new TaskNode(
                                node.nodeId(), node.capabilityKey(), node.title(), node.parameters(),
                                node.dependencies(), to, node.attemptCount(), node.hitlPolicy(),
                                node.evaluationMode(), node.qualityCriteria(), node.outputSummary(),
                                node.asyncTaskId(), node.error());
                    }
                    return node;
                })
                .toList();
        return withState(plan, changed.get() ? planState : TaskPlanState.FAILED, nodes);
    }

    private static TaskPlan withState(TaskPlan plan, TaskPlanState state, List<TaskNode> nodes) {
        return new TaskPlan(
                plan.planId(), plan.conversationId(), plan.goal(), state, plan.version(),
                plan.maxNodes(), plan.maxAttemptsPerNode(), plan.maxReplans(), plan.replanCount(),
                nodes, plan.edges(), plan.error(), plan.planSignature());
    }
}
