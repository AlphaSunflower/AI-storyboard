package com.storyboard.dto.request;

/**
 * 更新账号偏好：字段为 null 表示不修改。
 */
public record UpdateUserSettingsRequest(
    String defaultApprovalMode,
    String busySendBehavior,
    String workStepDisplay
) {}
