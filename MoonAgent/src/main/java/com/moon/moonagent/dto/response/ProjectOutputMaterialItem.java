package com.moon.moonagent.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/** 项目产出素材中的一个生成结果；一次生成可包含多张图片。 */
public record ProjectOutputMaterialItem(
        String id,
        String type,
        List<String> urls,
        String sceneId,
        OffsetDateTime createdAt
) {}
