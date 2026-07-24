package com.storyboard.dto.response;

public record LoginResponse(
    String accessToken,
    String refreshToken,
    String userId,
    String displayName
) {}
