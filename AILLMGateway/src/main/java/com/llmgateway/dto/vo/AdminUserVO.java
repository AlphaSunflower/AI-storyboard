package com.llmgateway.dto.vo;

import java.time.OffsetDateTime;

/**
 * 管理后台用户视图对象（对应实体 AdminUser）。
 * 不含 passwordHash（scrypt 哈希不对外暴露）与逻辑删除标记。
 */
public record AdminUserVO(
        String id,
        String username,
        String role,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
