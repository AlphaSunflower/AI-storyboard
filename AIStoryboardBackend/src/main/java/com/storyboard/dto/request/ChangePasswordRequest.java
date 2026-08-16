package com.storyboard.dto.request;

/**
 * 修改密码请求。
 */
public record ChangePasswordRequest(
    String oldPassword,
    String newPassword
) {}
