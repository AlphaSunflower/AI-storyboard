package com.moon.moonagent.ai.agent.sandbox;

public record SandboxReadResult(
        String path,
        String content,
        int offset,
        int nextOffset,
        int totalChars,
        boolean truncated
) {
}
