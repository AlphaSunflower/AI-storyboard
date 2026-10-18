package com.moon.moonagent.dto.response;

import java.time.OffsetDateTime;

public record SubagentRunView(
        String id,
        String graphId,
        String conversationId,
        String turnId,
        String toolCallId,
        String nodeId,
        String role,
        String status,
        String instruction,
        String output,
        String error,
        Integer tokens,
        Long durationMs,
        OffsetDateTime createdAt) {
}
