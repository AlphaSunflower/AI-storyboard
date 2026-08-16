package com.storyboard.dto.response;

/**
 * 当前用户个人信息 VO。
 */
public record ProfileResponse(
    String userId,
    String displayName,
    String email
) {}
