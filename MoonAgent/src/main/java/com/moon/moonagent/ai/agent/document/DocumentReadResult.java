package com.moon.moonagent.ai.agent.document;

public record DocumentReadResult(
        String assetId,
        String name,
        String mimeType,
        String summary,
        String status,
        int offset,
        int nextOffset,
        int totalChars,
        boolean truncated,
        String markdown
) {
}
