package com.llmgateway.dto.vo;

import java.time.OffsetDateTime;

/**
 * 渠道视图对象（对应实体 Channel）。
 * 不含 apiKey（AES 密文不对外暴露，前端永不接触密钥）。
 */
public record ChannelVO(
        String id,
        String name,
        String type,
        String baseUrl,
        String models,
        Boolean enabled,
        Integer priority,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
