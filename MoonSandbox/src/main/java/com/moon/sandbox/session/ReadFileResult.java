package com.moon.sandbox.session;

public record ReadFileResult(
        String path,
        String content,
        int offset,
        int nextOffset,
        int totalChars,
        boolean truncated
) {
}
