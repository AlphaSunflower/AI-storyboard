package com.moon.moonagent.dto.response;

import java.time.OffsetDateTime;

/** 账号级全局记忆的只读视图。 */
public record AgentGlobalMemoryVO(
        String content,
        OffsetDateTime updatedAt) {
}
