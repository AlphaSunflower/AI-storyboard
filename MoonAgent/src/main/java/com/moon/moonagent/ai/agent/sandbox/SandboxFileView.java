package com.moon.moonagent.ai.agent.sandbox;

public record SandboxFileView(
        String path,
        long sizeBytes,
        String mimeType
) {
}
