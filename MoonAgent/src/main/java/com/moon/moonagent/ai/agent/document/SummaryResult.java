package com.moon.moonagent.ai.agent.document;

public record SummaryResult(
        String summary,
        String status,
        boolean truncated
) {
}
