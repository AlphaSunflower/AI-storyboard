package com.moon.moonagent.orchestration;

import java.util.List;
import java.util.Map;

public record MediaToolResult(
        String jobId,
        String taskId,
        String mediaType,
        String status,
        List<String> resultUrls,
        String error,
        Map<String, Object> metadata
) {
    public MediaToolResult {
        resultUrls = resultUrls == null ? List.of() : List.copyOf(resultUrls);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
