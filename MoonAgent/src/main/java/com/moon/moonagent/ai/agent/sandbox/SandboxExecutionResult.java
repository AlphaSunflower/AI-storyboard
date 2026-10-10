package com.moon.moonagent.ai.agent.sandbox;

import java.util.List;

public record SandboxExecutionResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean stdoutTruncated,
        boolean stderrTruncated,
        boolean timedOut,
        long durationMs,
        List<SandboxFileView> files
) {
    public SandboxExecutionResult {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
