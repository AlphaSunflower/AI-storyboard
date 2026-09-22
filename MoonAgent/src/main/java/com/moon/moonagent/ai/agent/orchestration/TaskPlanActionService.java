package com.moon.moonagent.ai.agent.orchestration;

import com.moon.moonagent.orchestration.TaskNode;
import com.moon.moonagent.orchestration.TaskNodeState;
import com.moon.moonagent.orchestration.TaskPlan;
import com.moon.moonagent.orchestration.TaskPlanState;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class TaskPlanActionService {

    public TaskPlan apply(TaskPlan plan, String action) {
        String normalized = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "cancel" -> cancel(plan);
            case "approve", "resume" -> approve(plan);
            case "reject" -> reject(plan);
            case "retry" -> retry(plan);
            case "replan" -> throw new InvalidTaskPlanTransitionException(
                    "replan requires the planner-backed task plan controller");
            default -> throw new IllegalArgumentException("unsupported plan action: " + action);
        };
    }

    private static TaskPlan cancel(TaskPlan plan) {
        if (terminal(plan.state())) {
            throw new InvalidTaskPlanTransitionException(
                    "cannot cancel plan from state " + plan.state());
        }
        return withState(plan, TaskPlanState.CANCELLED, plan.nodes());
    }

    private static TaskPlan approve(TaskPlan plan) {
        TaskNode target = waitingHumanTarget(plan).orElseThrow(() ->
                new InvalidTaskPlanTransitionException("no waiting human node to approve"));
        return withNodeState(plan, target, TaskNodeState.READY, TaskPlanState.RUNNING);
    }

    private static TaskPlan reject(TaskPlan plan) {
        TaskNode target = waitingHumanTarget(plan).orElseThrow(() ->
                new InvalidTaskPlanTransitionException("no waiting human node to reject"));
        return withNodeState(plan, target, TaskNodeState.FAILED, TaskPlanState.FAILED);
    }

    private static TaskPlan retry(TaskPlan plan) {
        TaskNode target = plan.nodes().stream()
                .filter(node -> node.state() == TaskNodeState.FAILED)
                .findFirst()
                .orElseThrow(() -> new InvalidTaskPlanTransitionException("no failed node to retry"));
        return withNodeState(plan, target, TaskNodeState.READY, TaskPlanState.RUNNING);
    }

    private static Optional<TaskNode> waitingHumanTarget(TaskPlan plan) {
        Optional<TaskNode> explicit = plan.nodes().stream()
                .filter(node -> node.state() == TaskNodeState.WAITING_HUMAN)
                .findFirst();
        if (explicit.isPresent()) return explicit;
        if (plan.state() != TaskPlanState.WAITING_HUMAN) return Optional.empty();
        return plan.nodes().stream()
                .filter(node -> node.state() == TaskNodeState.PENDING || node.state() == TaskNodeState.READY)
                .filter(node -> node.hitlPolicy() != com.moon.moonagent.orchestration.HitlPolicy.NONE)
                .findFirst();
    }

    private static TaskPlan withNodeState(TaskPlan plan, TaskNode target,
                                          TaskNodeState to, TaskPlanState planState) {
        List<TaskNode> nodes = plan.nodes().stream()
                .map(node -> node.nodeId().equals(target.nodeId())
                        ? new TaskNode(
                        node.nodeId(), node.capabilityKey(), node.title(), node.parameters(),
                        node.dependencies(), to, node.attemptCount(), node.hitlPolicy(),
                        node.evaluationMode(), node.qualityCriteria(), node.outputSummary(),
                        node.asyncTaskId(), node.error())
                        : node)
                .toList();
        return withState(plan, planState, nodes);
    }

    private static TaskPlan withState(TaskPlan plan, TaskPlanState state, List<TaskNode> nodes) {
        return new TaskPlan(
                plan.planId(), plan.conversationId(), plan.goal(), state, plan.version(),
                plan.maxNodes(), plan.maxAttemptsPerNode(), plan.maxReplans(), plan.replanCount(),
                nodes, plan.edges(), plan.error(), plan.planSignature());
    }

    private static boolean terminal(TaskPlanState state) {
        return state == TaskPlanState.COMPLETED
                || state == TaskPlanState.FAILED
                || state == TaskPlanState.CANCELLED;
    }
}
