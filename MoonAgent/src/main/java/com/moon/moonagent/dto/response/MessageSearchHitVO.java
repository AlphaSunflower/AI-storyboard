package com.moon.moonagent.dto.response;

import java.time.OffsetDateTime;

public record MessageSearchHitVO(
        String messageId,
        String conversationId,
        String conversationTitle,
        String role,
        String content,
        OffsetDateTime createdAt
) {
}
