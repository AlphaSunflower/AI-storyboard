package com.storyboard.controller;

import com.storyboard.dto.request.LoginRequest;
import com.storyboard.dto.request.RegisterRequest;
import com.storyboard.dto.request.UnloginRequest;
import com.storyboard.dto.response.ApiResponse;
import com.storyboard.dto.response.LoginResponse;
import com.storyboard.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        return ApiResponse.ok(authService.refresh(refreshToken));
    }

    @PostMapping("/unlogin")
    public ApiResponse<Map<String, Object>> unlogin(
            @RequestBody UnloginRequest req,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return authService.handleUnlogin(req, authHeader);
    }
}
