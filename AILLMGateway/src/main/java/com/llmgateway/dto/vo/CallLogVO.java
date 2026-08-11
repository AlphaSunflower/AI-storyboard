package com.llmgateway.dto.vo;

import java.time.OffsetDateTime;

/** 调用日志视图对象（对应实体 CallLog，无敏感字段，全量透出） */
public record CallLogVO(
        String id,
        String model,
        String channelId,
        String status,
        Long durationMs,
        String error,
        String videoUrl,
        String taskId,
        OffsetDateTime createdAt
) {}
