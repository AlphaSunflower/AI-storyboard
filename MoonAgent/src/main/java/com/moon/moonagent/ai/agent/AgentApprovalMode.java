package com.moon.moonagent.ai.agent;

import com.storyboard.common.BusinessException;

import java.util.Locale;

/**
 * Per-conversation approval policy for AgentScope side effects.
 */
public enum AgentApprovalMode {

    /**
     * Show a detailed execution plan and wait for one human approval before the
     * listed high-risk tools run. This is the default for new conversations.
     */
    PLAN_APPROVAL,

    /**
     * Ask before every high-risk tool call. The model cannot bypass HITL.
     */
    REQUEST_APPROVAL,

    /**
     * Run the turn end-to-end without permission pauses.
     */
    FULL_ACCESS;

    public static AgentApprovalMode from(String raw) {
        if (raw == null || raw.isBlank()) return PLAN_APPROVAL;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return PLAN_APPROVAL;
        }
    }

    public static AgentApprovalMode require(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(40001, "审批模式不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            throw new BusinessException(40001, "审批模式非法");
        }
    }
}
