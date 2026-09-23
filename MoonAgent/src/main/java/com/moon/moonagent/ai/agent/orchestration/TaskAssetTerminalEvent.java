package com.moon.moonagent.ai.agent.orchestration;

public record TaskAssetTerminalEvent(
        String conversationId,
        String planId,
        String nodeId,
        boolean success,
        String summary
) {
}
