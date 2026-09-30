package com.storyboard.controller;

import com.storyboard.dto.response.AuthResult;
import com.storyboard.dto.request.LoginRequest;
import com.storyboard.dto.request.RegisterRequest;
import com.storyboard.dto.request.UnloginRequest;
import com.storyboard.common.ApiResponse;
import com.storyboard.dto.response.LoginResponse;
import com.storyboard.service.AuthService;
import com.storyboard.security.AuthSessionService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthSessionService sessionService;

    @Value("${auth.cookie.secure:true}")
    private boolean secureCookie;
    @Value("${jwt.access-token-ttl:7200}")
    private long accessTokenTtlSeconds;

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletResponse response) {
        AuthResult result = authService.login(request);
        writeRefreshCookie(response, result.refreshToken());
        writeMediaCookie(response, result.response().accessToken());
        return ApiResponse.ok(result.response());
    }

    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(@Valid @RequestBody RegisterRequest request,
                                               HttpServletResponse response) {
        AuthResult result = authService.register(request);
        writeRefreshCookie(response, result.refreshToken());
        writeMediaCookie(response, result.response().accessToken());
        return ApiResponse.ok(result.response());
    }

    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(
            @RequestBody(required = false) Map<String, String> body,
            @CookieValue(value = "refresh_token", required = false) String cookieToken,
            HttpServletResponse response) {
        String refreshToken = cookieToken;
        if ((refreshToken == null || refreshToken.isBlank()) && body != null) {
            refreshToken = body.get("refreshToken");
        }
        AuthResult result = authService.refresh(refreshToken);
        writeRefreshCookie(response, result.refreshToken());
        writeMediaCookie(response, result.response().accessToken());
        return ApiResponse.ok(result.response());
    }

    @PostMapping("/unlogin")
    public ApiResponse<Map<String, Object>> unlogin(
            @RequestBody UnloginRequest req,
            @CookieValue(value = "refresh_token", required = false) String refreshToken,
            HttpServletResponse response) {
        expireRefreshCookie(response);
        expireMediaCookie(response);
        return authService.handleUnlogin(req, refreshToken);
    }

    @PostMapping("/stream-ticket")
    public ApiResponse<Map<String, Object>> streamTicket(
            Authentication auth,
            @RequestBody Map<String, String> body) {
        AuthSessionService.StreamTicket ticket =
                sessionService.issueStreamTicket(auth.getName(), body.get("path"));
        return ApiResponse.ok(Map.of(
                "ticket", ticket.ticket(),
                "expiresAt", ticket.expiresAt()));
    }

    private void writeRefreshCookie(HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from("refresh_token", token)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(Duration.ofDays(30))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void expireRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from("refresh_token", "")
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void writeMediaCookie(HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from("media_token", token)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/api/files")
                .maxAge(Duration.ofSeconds(Math.max(60, accessTokenTtlSeconds)))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void expireMediaCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from("media_token", "")
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/api/files")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
