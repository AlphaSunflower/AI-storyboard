package com.moon.moonagent.ai.agent;

public record AgentReferenceUpload(
        String assetId,
        String url,
        String name,
        String mimeType,
        Long sizeBytes
) {
}
