package com.storyboard.dto.request;

/**
 * 更新个人信息请求：displayName / email 至少一个非空。
 */
public record UpdateProfileRequest(
    String displayName,
    String email
) {}
