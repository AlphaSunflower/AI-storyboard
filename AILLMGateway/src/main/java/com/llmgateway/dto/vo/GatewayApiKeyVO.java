package com.llmgateway.dto.vo;

import java.time.OffsetDateTime;

/**
 * 业务调用 Key 视图对象（对应实体 GatewayApiKey）。
 * 不含 keyHash（SHA-256 哈希不对外暴露）。
 */
public record GatewayApiKeyVO(
        String id,
        String name,
        Boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
