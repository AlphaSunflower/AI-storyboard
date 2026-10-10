package com.moon.moonagent.ai.agent.sandbox;

public record SandboxWriteResult(
        String path,
        long sizeBytes,
        String sha256
) {
}
