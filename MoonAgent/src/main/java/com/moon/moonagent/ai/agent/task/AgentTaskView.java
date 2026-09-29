package com.moon.moonagent.ai.agent.task;

import java.time.OffsetDateTime;
import java.util.List;

public record AgentTaskView(
        String taskId,
        String conversationId,
        String projectId,
        String title,
        String toolName,
        String kind,
        String status,
        String stage,
        Integer progress,
        String resultSummary,
        String error,
        String parentTaskId,
        String anchorMessageId,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime completedAt,
        List<String> assetIds,
        List<String> resultUrls,
        List<AgentTaskSlotView> slots,
        String result,
        String liveText,
        String liveThinking,
        String liveStage,
        OffsetDateTime liveUpdatedAt,
        boolean canCancel,
        boolean canRetry,
        boolean canFollowUp
) {
}
