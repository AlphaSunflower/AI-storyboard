package com.storyboard.dto.response;

import java.time.OffsetDateTime;

/**
 * Agent 会话视图对象（对应实体 AgentConversation）。
 * 用于 /api/agent/** 接口返回，避免将 MyBatis-Plus 实体直接暴露给前端。
 */
public record AgentConversationVO(
    String id,
    String userId,
    String projectId,
    String title,
    String status,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {}