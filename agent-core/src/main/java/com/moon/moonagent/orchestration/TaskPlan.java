package com.moon.moonagent.orchestration;

import java.util.List;

public record TaskPlan(
        String planId,
        String conversationId,
        String goal,
        TaskPlanState state,
        int version,
        int maxNodes,
        int maxAttemptsPerNode,
        int maxReplans,
        int replanCount,
        List<TaskNode> nodes,
        List<TaskEdge> edges,
        String error,
        String planSignature
) {
    public TaskPlan {
        state = state == null ? TaskPlanState.DRAFT : state;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        error = error == null ? "" : error;
        planSignature = planSignature == null ? "" : planSignature;
    }

    public TaskPlan withBudget(int maxNodes, int maxAttemptsPerNode, int maxReplans) {
        return new TaskPlan(
                planId, conversationId, goal, state, version,
                maxNodes, maxAttemptsPerNode, maxReplans, replanCount,
                nodes, edges, error, planSignature);
    }
}
