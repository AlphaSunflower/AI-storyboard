package com.storyboard.dto.response;

/**
 * 当前用户个人信息 VO。role 用于前端判断是否展示管理员入口（如反馈收件箱）。
 */
public record ProfileResponse(
    String userId,
    String displayName,
    String email,
    String role
) {}
