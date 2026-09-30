package com.moon.moonagent.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 创建 Agent 对话会话请求（userId 由 JWT 提供，不入 DTO） */
public record AgentCreateConversationRequest(
    @NotBlank(message = "项目ID不能为空") String projectId,
    @Size(max = 200, message = "会话标题过长") String title,
    String orchestrationMode,
    String approvalMode
) {}
