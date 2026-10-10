package com.moon.sandbox.session;

import java.time.OffsetDateTime;

public record FileEntry(
        String path,
        boolean directory,
        long sizeBytes,
        String mimeType,
        OffsetDateTime modifiedAt
) {
}
