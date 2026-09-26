package com.moon.moonagent.ai.agent.recovery;

import java.util.Map;

public record RecoveryCandidate(
        String resourceType,
        String resourceId,
        String conversationId,
        Map<String, Object> attributes) {

    public RecoveryCandidate {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
