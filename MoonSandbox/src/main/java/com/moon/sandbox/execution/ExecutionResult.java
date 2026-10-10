package com.moon.sandbox.execution;

import java.util.List;

public record ExecutionResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean stdoutTruncated,
        boolean stderrTruncated,
        boolean timedOut,
        long durationMs,
        List<FileEntryView> files
) {
}
