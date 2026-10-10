package com.moon.sandbox.execution;

public record FileEntryView(
        String path,
        long sizeBytes,
        String mimeType
) {
}
