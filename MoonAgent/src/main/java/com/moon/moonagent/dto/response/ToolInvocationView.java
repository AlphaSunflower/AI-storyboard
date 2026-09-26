package com.moon.moonagent.dto.response;

import com.moon.moonagent.entity.AgentToolInvocation;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

public record ToolInvocationView(
        String id,
        String traceId,
        String loopRunId,
        String loopStepId,
        String taskId,
        String conversationId,
        String projectId,
        String toolCallId,
        String toolName,
        String toolKind,
        String source,
        String status,
        Integer attempt,
        String inputHash,
        String inputSummary,
        String outputHash,
        String outputSummary,
        String errorCode,
        String errorMessage,
        String externalActionKey,
        String providerTaskId,
        List<String> resultAssetIds,
        Integer expectedReferenceCount,
        Integer resolvedReferenceCount,
        Integer appliedReferenceCount,
        String skillVersion,
        String model,
        String engineVersion,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        Long durationMs
) {

    public static ToolInvocationView from(AgentToolInvocation invocation) {
        if (invocation == null) return null;
        return new ToolInvocationView(
                invocation.getId(),
                invocation.getTraceId(),
                invocation.getLoopRunId(),
                invocation.getLoopStepId(),
                invocation.getTaskId(),
                invocation.getConversationId(),
                invocation.getProjectId(),
                invocation.getToolCallId(),
                invocation.getToolName(),
                invocation.getToolKind(),
                invocation.getSource(),
                invocation.getStatus(),
                invocation.getAttempt(),
                invocation.getInputHash(),
                invocation.getInputSummary(),
                invocation.getOutputHash(),
                invocation.getOutputSummary(),
                invocation.getErrorCode(),
                invocation.getErrorMessage(),
                invocation.getExternalActionKey(),
                invocation.getProviderTaskId(),
                split(invocation.getResultAssetIds()),
                invocation.getExpectedReferenceCount(),
                invocation.getResolvedReferenceCount(),
                invocation.getAppliedReferenceCount(),
                invocation.getSkillVersion(),
                invocation.getModel(),
                invocation.getEngineVersion(),
                invocation.getStartedAt(),
                invocation.getCompletedAt(),
                invocation.getDurationMs());
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }
}
