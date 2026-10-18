package com.moon.moonagent.dto.response;

import java.time.OffsetDateTime;

/** 项目级长期记忆的只读视图。 */
public record AgentMemoryVO(
        String id,
        String projectId,
        String conversationId,
        String kind,
        String content,
        String source,
        Double confidence,
        OffsetDateTime expiresAt,
        OffsetDateTime updatedAt) {
}
