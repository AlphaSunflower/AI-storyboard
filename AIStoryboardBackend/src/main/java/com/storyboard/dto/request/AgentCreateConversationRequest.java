package com.storyboard.dto.request;

import jakarta.validation.constraints.NotBlank;

/** 创建 Agent 对话会话请求（userId 由 JWT 提供，不入 DTO） */
public record AgentCreateConversationRequest(
    @NotBlank(message = "项目ID不能为空") String projectId,
    String title
) {}
