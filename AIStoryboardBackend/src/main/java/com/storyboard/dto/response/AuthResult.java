package com.storyboard.dto.response;

/** Internal authentication result: response body plus refresh cookie value. */
public record AuthResult(LoginResponse response, String refreshToken) {
}
