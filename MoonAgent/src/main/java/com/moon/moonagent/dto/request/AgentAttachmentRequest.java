package com.moon.moonagent.dto.request;

/**
 * A file attached to one user message. The URL remains the model-facing reference;
 * name/mime metadata is persisted so the conversation can render a stable file row
 * after refresh instead of showing a raw URL.
 */
public record AgentAttachmentRequest(
        String url,
        String name,
        String mimeType,
        Long sizeBytes,
        String kind
) {
}
