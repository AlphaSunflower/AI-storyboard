package com.llmgateway.dto.vo;

import java.time.OffsetDateTime;

/** 模型路由视图对象（对应实体 ModelRoute，不含逻辑删除标记） */
public record ModelRouteVO(
        String id,
        String modelName,
        String channelId,
        String type,
        String defaultParams,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
