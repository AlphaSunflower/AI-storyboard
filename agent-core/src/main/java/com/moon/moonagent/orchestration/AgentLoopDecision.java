package com.moon.moonagent.orchestration;

import java.util.Map;

public record AgentLoopDecision(
        AgentLoopDecisionType type,
        ChainType currentChain,
        ChainType targetChain,
        String reason,
        Map<String, Object> state
) {
    public AgentLoopDecision {
        state = state == null ? Map.of() : Map.copyOf(state);
    }
}
