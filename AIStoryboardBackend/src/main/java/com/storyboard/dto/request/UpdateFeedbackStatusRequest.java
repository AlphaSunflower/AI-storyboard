package com.storyboard.dto.request;

/**
 * 管理员处理反馈：status 只允许 open / resolved。
 */
public record UpdateFeedbackStatusRequest(String status) {}
