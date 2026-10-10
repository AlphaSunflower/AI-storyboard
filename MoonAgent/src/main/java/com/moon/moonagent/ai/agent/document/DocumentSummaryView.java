package com.moon.moonagent.ai.agent.document;

public record DocumentSummaryView(
        String assetId,
        String name,
        String mimeType,
        String summary,
        String status
) {
}
