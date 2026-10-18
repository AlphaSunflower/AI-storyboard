package com.moon.moonagent.dto.response;

import java.time.OffsetDateTime;

/** 动态包只读视图（不暴露 manifest 正文）。 */
public record AgentPackageVO(
        String id,
        String kind,
        String name,
        String version,
        boolean enabled,
        OffsetDateTime updatedAt) {
}
