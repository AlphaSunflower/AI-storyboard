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
        /**
         * 工具卡归属：复合旅程父任务为自身的 {@code toolCallId}，
         * 复合旅程子任务为其父任务的 {@code toolCallId}；
         * 非旅程任务一律为空字符串（前端保持既有独立卡片渲染）。
         */
        String parentToolCallId,
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
