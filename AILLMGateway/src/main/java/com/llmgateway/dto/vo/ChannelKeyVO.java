package com.llmgateway.dto.vo;

import java.time.OffsetDateTime;

/** 渠道 Key 视图：永不返回明文/密文，只回是否可用与冷却状态 */
public record ChannelKeyVO(
        String id,
        String channelId,
        String label,
        String maskedKey,
        Integer weight,
        Boolean enabled,
        Integer failCount,
        OffsetDateTime cooldownUntil,
        OffsetDateTime lastUsedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
