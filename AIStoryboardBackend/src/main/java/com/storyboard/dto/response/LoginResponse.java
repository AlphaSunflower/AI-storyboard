package com.storyboard.dto.response;

/**
 * 登录/注册/刷新的响应体。
 *
 * <p>{@code gatewayToken} 是给桌面端调用 LLM 网关的长期凭据（typ=gateway，30 天），
 * 浏览器端忽略该字段；有它之后客户端不再内置共享网关 Key。
 */
public record LoginResponse(
    String accessToken,
    String userId,
    String displayName,
    String gatewayToken
) {}
