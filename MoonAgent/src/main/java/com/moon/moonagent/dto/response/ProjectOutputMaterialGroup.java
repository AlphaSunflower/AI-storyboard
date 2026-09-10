package com.moon.moonagent.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/** 项目产出素材按对话分组。 */
public record ProjectOutputMaterialGroup(
        String conversationId,
        String title,
        OffsetDateTime updatedAt,
        List<ProjectOutputMaterialItem> items
) {}
