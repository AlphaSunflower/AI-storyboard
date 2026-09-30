package com.storyboard.dto.response;

public record LoginResponse(
    String accessToken,
    String userId,
    String displayName
) {}
