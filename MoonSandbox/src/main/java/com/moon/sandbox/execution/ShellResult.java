package com.moon.sandbox.execution;

public record ShellResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean truncated,
        boolean timedOut,
        long durationMs
) {
}
