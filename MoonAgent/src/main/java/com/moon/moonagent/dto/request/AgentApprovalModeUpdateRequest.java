package com.moon.moonagent.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Updates the approval policy for one existing AgentScope conversation.
 */
public record AgentApprovalModeUpdateRequest(
    @NotBlank(message = "审批模式不能为空") String approvalMode
) {}
