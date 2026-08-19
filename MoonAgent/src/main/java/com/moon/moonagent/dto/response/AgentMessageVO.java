package com.moon.moonagent.dto.response;

import java.time.OffsetDateTime;

/**
 * Agent 消息视图对象（对应实体 AgentMessage）。
 * 用于 /api/agent/** 接口返回，避免将 MyBatis-Plus 实体直接暴露给前端。
 */
public record AgentMessageVO(
    String id,
    String conversationId,
    String role,
    String content,
    OffsetDateTime createdAt
) {}