package com.storyboard.dto.response;

/**
 * 账号偏好设置。缺行时返回默认值。
 */
public record UserSettingsResponse(
    String defaultApprovalMode,
    String busySendBehavior,
    String workStepDisplay
) {}
