package com.moon.moonagent.orchestration;

import java.util.Map;

public record TaskArtifact(
        String artifactId,
        String planId,
        String nodeId,
        String assetId,
        String kind,
        String url,
        Map<String, Object> metadata
) {
    public TaskArtifact {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
