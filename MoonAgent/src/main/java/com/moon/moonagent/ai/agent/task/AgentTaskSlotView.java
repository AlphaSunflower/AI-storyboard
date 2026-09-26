package com.moon.moonagent.ai.agent.task;

import java.time.OffsetDateTime;

public record AgentTaskSlotView(
        int index,
        String label,
        String status,
        String prompt,
        String url,
        String assetId,
        String error,
        OffsetDateTime completedAt
) {
}
