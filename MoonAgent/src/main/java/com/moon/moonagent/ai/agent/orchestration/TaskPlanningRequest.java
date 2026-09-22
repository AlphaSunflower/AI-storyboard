package com.moon.moonagent.ai.agent.orchestration;

public record TaskPlanningRequest(
        String conversationId,
        String goal,
        String preferredCapability,
        int maxNodes,
        int maxAttemptsPerNode,
        int maxReplans
) {
}
