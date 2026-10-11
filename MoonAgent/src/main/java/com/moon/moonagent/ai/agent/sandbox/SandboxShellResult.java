package com.moon.moonagent.ai.agent.sandbox;

public record SandboxShellResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean truncated,
        boolean timedOut,
        long durationMs
) {
}
