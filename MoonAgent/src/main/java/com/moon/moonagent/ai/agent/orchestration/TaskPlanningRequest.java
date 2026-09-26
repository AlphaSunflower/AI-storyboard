package com.moon.moonagent.ai.agent.orchestration;

public record TaskPlanningRequest(
        String conversationId,
        String goal,
        String preferredCapability,
        int maxNodes,
        int maxAttemptsPerNode,
        int maxReplans,
        com.moon.moonagent.ai.agent.handler.AgentTraceAccumulator trace
) {
    public TaskPlanningRequest(String conversationId, String goal, String preferredCapability,
                               int maxNodes, int maxAttemptsPerNode, int maxReplans) {
        this(conversationId, goal, preferredCapability, maxNodes, maxAttemptsPerNode, maxReplans, null);
    }
}
