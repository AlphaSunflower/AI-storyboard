package com.storyboard.dto.request;

/** 创建 Agent 对话会话请求（userId 由 JWT 提供，不入 DTO） */
public record AgentCreateConversationRequest(
    String projectId,
    String title
) {}
