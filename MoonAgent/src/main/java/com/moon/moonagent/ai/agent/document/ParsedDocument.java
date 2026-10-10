package com.moon.moonagent.ai.agent.document;

public record ParsedDocument(
        String markdown,
        String mimeType,
        int charCount,
        boolean truncated
) {
}
