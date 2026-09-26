package com.moon.moonagent.ai.agent.turn;

import java.util.Map;

public record AgentTurnEventEnvelope(
        String turnId,
        int seq,
        String type,
        Map<String, Object> payload
) {
}

