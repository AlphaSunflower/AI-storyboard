package com.moon.moonagent.orchestration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@org.springframework.stereotype.Component
public class TaskPlanScheduler {

    public TaskPlan advance(TaskPlan plan) {
        Map<String, List<String>> dependencies = dependencies(plan);
        Map<String, TaskNode> byId = new LinkedHashMap<>();
        for (TaskNode node : plan.nodes()) {
            byId.put(node.nodeId(), node);
        }

        List<TaskNode> updated = new ArrayList<>();
        for (TaskNode node : plan.nodes()) {
            TaskNodeState next = node.state();
            if (node.state() == TaskNodeState.PENDING) {
                List<String> deps = dependencies.getOrDefault(node.nodeId(), List.of());
                boolean blocked = deps.stream()
                        .map(byId::get)
                        .filter(java.util.Objects::nonNull)
                        .anyMatch(dep -> dep.state() == TaskNodeState.FAILED || dep.state() == TaskNodeState.SKIPPED);
                boolean ready = deps.stream()
                        .map(byId::get)
                        .allMatch(dep -> dep != null && dep.state() == TaskNodeState.COMPLETED);
                if (blocked) next = TaskNodeState.SKIPPED;
                else if (ready) next = TaskNodeState.READY;
            }
            updated.add(withState(node, next, node.attemptCount(), node.outputSummary(), node.error()));
        }

        TaskPlan advanced = withNodesAndState(plan, updated, derivePlanState(plan.state(), updated));
        if (advanced.nodes().size() > plan.nodes().size()) {
            return advanced;
        }
        if (hasUnpropagatedSkips(plan.nodes(), advanced.nodes())) {
            return advance(advanced);
        }
        return advanced;
    }

    public Optional<TaskNode> nextReady(TaskPlan plan) {
        return plan.nodes().stream()
                .filter(node -> node.state() == TaskNodeState.READY)
                .findFirst();
    }

    public TaskPlan completeNode(TaskPlan plan, String nodeId, String outputSummary) {
        List<TaskNode> nodes = plan.nodes().stream()
                .map(node -> node.nodeId().equals(nodeId)
                        ? withState(node, TaskNodeState.COMPLETED, node.attemptCount(), outputSummary, "")
                        : node)
                .toList();
        return advance(withNodesAndState(plan, nodes, TaskPlanState.RUNNING));
    }

    public TaskPlan failNode(TaskPlan plan, String nodeId, String error) {
        List<TaskNode> nodes = plan.nodes().stream()
                .map(node -> {
                    if (!node.nodeId().equals(nodeId)) return node;
                    int attempts = node.attemptCount() + 1;
                    TaskNodeState next = attempts >= plan.maxAttemptsPerNode()
                            ? TaskNodeState.FAILED
                            : TaskNodeState.READY;
                    return withState(node, next, attempts, node.outputSummary(), error);
                })
                .toList();
        return advance(withNodesAndState(plan, nodes, TaskPlanState.RUNNING));
    }

    /** 标记当前节点永久失败并停止计划，供不可重试的能力/工具错误使用。 */
    public TaskPlan failNodeTerminal(TaskPlan plan, String nodeId, String error) {
        List<TaskNode> nodes = plan.nodes().stream()
                .map(node -> node.nodeId().equals(nodeId)
                        ? withState(node, TaskNodeState.FAILED, node.attemptCount() + 1,
                        node.outputSummary(), error)
                        : node)
                .toList();
        return advance(withNodesAndState(plan, nodes, TaskPlanState.FAILED));
    }

    public TaskPlan markRunning(TaskPlan plan, String nodeId) {
        List<TaskNode> nodes = plan.nodes().stream()
                .map(node -> node.nodeId().equals(nodeId)
                        ? withState(node, TaskNodeState.RUNNING, node.attemptCount(), node.outputSummary(), node.error())
                        : node)
                .toList();
        return withNodesAndState(plan, nodes, TaskPlanState.RUNNING);
    }

    public TaskPlan markWaitingHuman(TaskPlan plan, String nodeId) {
        return markWaitingHuman(plan, nodeId, "");
    }

    public TaskPlan markWaitingHuman(TaskPlan plan, String nodeId, String outputSummary) {
        List<TaskNode> nodes = plan.nodes().stream()
                .map(node -> node.nodeId().equals(nodeId)
                        ? withState(node, TaskNodeState.WAITING_HUMAN, node.attemptCount(), outputSummary, node.error())
                        : node)
                .toList();
        return withNodesAndState(plan, nodes, TaskPlanState.WAITING_HUMAN);
    }

    public TaskPlan markWaitingAsync(TaskPlan plan, String nodeId, String taskId) {
        return markWaitingAsync(plan, nodeId, taskId, "");
    }

    public TaskPlan markWaitingAsync(TaskPlan plan, String nodeId, String taskId, String outputSummary) {
        List<TaskNode> nodes = plan.nodes().stream()
                .map(node -> node.nodeId().equals(nodeId)
                        ? new TaskNode(node.nodeId(), node.capabilityKey(), node.title(), node.parameters(),
                        node.dependencies(), TaskNodeState.WAITING_ASYNC, node.attemptCount(), node.hitlPolicy(),
                        node.evaluationMode(), node.qualityCriteria(), outputSummary, taskId, node.error())
                        : node)
                .toList();
        return withNodesAndState(plan, nodes, TaskPlanState.WAITING_ASYNC);
    }

    public TaskPlan replan(TaskPlan plan, List<TaskNode> replacements, List<TaskEdge> replacementEdges) {
        Map<String, TaskNode> replacementById = new LinkedHashMap<>();
        for (TaskNode replacement : replacements) {
            replacementById.put(replacement.nodeId(), replacement);
        }

        List<TaskNode> nodes = new ArrayList<>();
        for (TaskNode node : plan.nodes()) {
            if (node.state() == TaskNodeState.COMPLETED) {
                nodes.add(node);
                replacementById.remove(node.nodeId());
            }
        }
        nodes.addAll(replacementById.values());

        return new TaskPlan(
                plan.planId(), plan.conversationId(), plan.goal(), TaskPlanState.READY,
                plan.version() + 1, plan.maxNodes(), plan.maxAttemptsPerNode(), plan.maxReplans(),
                plan.replanCount() + 1, nodes,
                replacementEdges == null ? List.of() : replacementEdges,
                "", plan.planSignature());
    }

    public TaskPlan recoverInterrupted(TaskPlan plan) {
        List<TaskNode> nodes = plan.nodes().stream()
                .map(node -> node.state() == TaskNodeState.RUNNING
                        ? withState(node, TaskNodeState.READY, node.attemptCount(),
                        node.outputSummary(), node.error())
                        : node)
                .toList();
        return withNodesAndState(plan, nodes, TaskPlanState.RUNNING);
    }

    private static Map<String, List<String>> dependencies(TaskPlan plan) {
        Map<String, List<String>> dependencies = new LinkedHashMap<>();
        Set<String> nodeIds = new HashSet<>();
        for (TaskNode node : plan.nodes()) {
            nodeIds.add(node.nodeId());
            dependencies.put(node.nodeId(), new ArrayList<>(node.dependencies()));
        }
        for (TaskEdge edge : plan.edges()) {
            if (nodeIds.contains(edge.toNodeId()) && nodeIds.contains(edge.fromNodeId())) {
                List<String> values = dependencies.computeIfAbsent(edge.toNodeId(), ignored -> new ArrayList<>());
                if (!values.contains(edge.fromNodeId())) values.add(edge.fromNodeId());
            }
        }
        return dependencies;
    }

    private static TaskPlanState derivePlanState(TaskPlanState current, List<TaskNode> nodes) {
        if (nodes.stream().anyMatch(node -> node.state() == TaskNodeState.WAITING_HUMAN)) {
            return TaskPlanState.WAITING_HUMAN;
        }
        if (nodes.stream().anyMatch(node -> node.state() == TaskNodeState.WAITING_ASYNC)) {
            return TaskPlanState.WAITING_ASYNC;
        }
        if (nodes.stream().allMatch(node -> node.state() == TaskNodeState.COMPLETED)) {
            return TaskPlanState.COMPLETED;
        }
        boolean terminalFailure = nodes.stream().anyMatch(node -> node.state() == TaskNodeState.FAILED)
                && nodes.stream().noneMatch(node -> node.state() == TaskNodeState.READY
                || node.state() == TaskNodeState.RUNNING
                || node.state() == TaskNodeState.PENDING);
        if (terminalFailure) {
            return TaskPlanState.FAILED;
        }
        if (nodes.stream().anyMatch(node -> node.state() == TaskNodeState.RUNNING)) {
            return TaskPlanState.RUNNING;
        }
        return current == TaskPlanState.DRAFT ? TaskPlanState.READY : current;
    }

    private static boolean hasUnpropagatedSkips(List<TaskNode> before, List<TaskNode> after) {
        Map<String, TaskNode> previous = new LinkedHashMap<>();
        for (TaskNode node : before) previous.put(node.nodeId(), node);
        for (TaskNode node : after) {
            TaskNode old = previous.get(node.nodeId());
            if (old != null && old.state() != node.state()) return true;
        }
        return false;
    }

    private static TaskPlan withNodesAndState(TaskPlan plan, List<TaskNode> nodes, TaskPlanState state) {
        return new TaskPlan(
                plan.planId(), plan.conversationId(), plan.goal(), state, plan.version(),
                plan.maxNodes(), plan.maxAttemptsPerNode(), plan.maxReplans(), plan.replanCount(),
                nodes, plan.edges(), plan.error(), plan.planSignature());
    }

    private static TaskNode withState(TaskNode node, TaskNodeState state, int attempts,
                                      String outputSummary, String error) {
        return new TaskNode(
                node.nodeId(), node.capabilityKey(), node.title(), node.parameters(), node.dependencies(),
                state, attempts, node.hitlPolicy(), node.evaluationMode(), node.qualityCriteria(),
                outputSummary, node.asyncTaskId(), error);
    }
}
