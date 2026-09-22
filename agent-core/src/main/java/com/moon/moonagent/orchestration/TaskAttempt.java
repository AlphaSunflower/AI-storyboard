package com.moon.moonagent.orchestration;

import java.time.OffsetDateTime;

public record TaskAttempt(
        String attemptId,
        String planId,
        String nodeId,
        int attemptNumber,
        String status,
        String inputSummary,
        String outputSummary,
        String error,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt
) {
}
