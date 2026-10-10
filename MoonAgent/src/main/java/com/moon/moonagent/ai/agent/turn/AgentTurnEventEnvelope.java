package com.moon.moonagent.ai.agent.turn;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Map;

public record AgentTurnEventEnvelope(
        String turnId,
        int seq,
        String type,
        Map<String, Object> payload,
        @JsonIgnore String payloadJson
) {
    public AgentTurnEventEnvelope(String turnId, int seq, String type, Map<String, Object> payload) {
        this(turnId, seq, type, payload, null);
    }
}

