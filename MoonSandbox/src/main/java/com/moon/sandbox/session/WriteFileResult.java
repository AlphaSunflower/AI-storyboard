package com.moon.sandbox.session;

public record WriteFileResult(
        String path,
        long sizeBytes,
        String sha256
) {
}
