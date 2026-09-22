package com.moon.moonagent.orchestration;

import java.util.Map;

public record AgentLoopDecision(
        AgentLoopDecisionType type,
        String planId,
        String nodeId,
        String targetNodeId,
        String reason,
        Map<String, Object> state
) {
    public AgentLoopDecision {
        planId = planId == null ? "" : planId;
        nodeId = nodeId == null ? "" : nodeId;
        targetNodeId = targetNodeId == null ? "" : targetNodeId;
        state = state == null ? Map.of() : Map.copyOf(state);
    }
}
