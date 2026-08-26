package com.moon.xiaoyuecanvas.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Morrow 作业提交体（契约 {@code POST /jobs}）。
 */
public record JobRequest(
        @NotBlank String clientRequestId,
        String conversationId,
        @NotBlank String capabilityId,
        List<String> sourceAssetIds,
        JobContext context,
        Map<String, Object> params
) {
    public record JobContext(String projectId, List<String> nodeIds, String selectedNodeId) {}
}
