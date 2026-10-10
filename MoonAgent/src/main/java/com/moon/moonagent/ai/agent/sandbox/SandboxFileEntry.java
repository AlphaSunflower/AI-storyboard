package com.moon.moonagent.ai.agent.sandbox;

import java.time.OffsetDateTime;

public record SandboxFileEntry(
        String path,
        boolean directory,
        long sizeBytes,
        String mimeType,
        OffsetDateTime modifiedAt
) {
}
