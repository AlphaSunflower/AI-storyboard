package com.storyboard.common.generation;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨 Moon Agent 与 AI 分镜服务统一的生成记录。
 *
 * <p>conversationId 可为空；编辑器直接生成时使用 userId + projectId + sceneId 归属。
 */
public record GenerationRecord(
        String userId,
        String projectId,
        String conversationId,
        String sceneId,
        String type,
        String genType,
        String prompt,
        String model,
        String taskId,
        String url,
        List<String> resultUrls,
        List<GenerationReference> referenceUrls,
        Map<String, Object> params,
        String status,
        String error,
        Long durationMs,
        OffsetDateTime createdAt
) {

    public GenerationRecord {
        resultUrls = resultUrls == null ? List.of() : List.copyOf(resultUrls);
        referenceUrls = referenceUrls == null ? List.of() : List.copyOf(referenceUrls);
        params = params == null || params.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(params));
        createdAt = createdAt == null ? OffsetDateTime.now() : createdAt;
    }
}
