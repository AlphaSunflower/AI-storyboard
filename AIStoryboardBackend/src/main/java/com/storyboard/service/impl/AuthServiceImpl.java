package com.storyboard.service.impl;

import com.storyboard.dto.request.LoginRequest;
import com.storyboard.dto.request.RegisterRequest;
import com.storyboard.dto.request.UnloginRequest;
import com.storyboard.common.ApiResponse;
import com.storyboard.dto.response.AuthResult;
import com.storyboard.dto.response.LoginResponse;
import com.storyboard.entity.User;
import com.storyboard.common.BusinessException;
import com.storyboard.mapper.UserMapper;
import com.storyboard.security.AuthSessionService;
import com.storyboard.security.JwtTokenProvider;
import com.storyboard.security.ScryptPasswordService;
import com.storyboard.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 认证服务实现：登录、注册、刷新 Token、未登录处理。
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final JwtTokenProvider jwtTokenProvider;
    private final ScryptPasswordService passwordService;
    private final AuthSessionService sessionService;

    @Override
    public AuthResult login(LoginRequest request) {
        User user = userMapper.findByEmail(request.email().toLowerCase().trim());
        if (user == null) {
            throw new BusinessException(40102, "用户名或密码错误");
        }
        if (!passwordService.verifyPassword(request.password(), user.getPasswordHash())) {
            throw new BusinessException(40102, "用户名或密码错误");
        }
        userMapper.updateLastLoginAt(user.getId(), OffsetDateTime.now());

        AuthSessionService.SessionTokens tokens = sessionService.issue(user);
        return new AuthResult(
                new LoginResponse(tokens.accessToken(), user.getId(), user.getDisplayName()),
                tokens.refreshToken());
    }

    @Override
    public AuthResult register(RegisterRequest request) {
        User existing = userMapper.findByEmail(request.email().toLowerCase().trim());
        if (existing != null) {
            throw new BusinessException(40001, "邮箱已被注册");
        }
        User user = new User();
        user.setEmail(request.email().toLowerCase().trim());
        user.setDisplayName(request.displayName());
        user.setRole("member");
        user.setStatus("enabled");
        try {
            user.setPasswordHash(passwordService.hashPassword(request.password()));
        } catch (Exception e) {
            throw new BusinessException(50000, "密码加密失败");
        }
        userMapper.insert(user);

        AuthSessionService.SessionTokens tokens = sessionService.issue(user);
        return new AuthResult(
                new LoginResponse(tokens.accessToken(), user.getId(), user.getDisplayName()),
                tokens.refreshToken());
    }

    @Override
    public AuthResult refresh(String refreshToken) {
        try {
            var claims = jwtTokenProvider.verifyRefreshToken(refreshToken);
            String userId = claims.getSubject();
            User user = userMapper.selectById(userId);
            if (user == null || "disabled".equals(user.getStatus())) {
                throw new BusinessException(40101, "用户不存在或已禁用");
            }
            AuthSessionService.SessionTokens tokens = sessionService.rotate(refreshToken);
            return new AuthResult(
                    new LoginResponse(tokens.accessToken(), user.getId(), user.getDisplayName()),
                    tokens.refreshToken());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(40101, "Token 无效或已过期");
        }
    }

    @Override
    public ApiResponse<Map<String, Object>> handleUnlogin(UnloginRequest req, String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            try {
                var claims = jwtTokenProvider.verifyRefreshToken(refreshToken);
                sessionService.revokeSession(claims.get("sid", String.class));
            } catch (Exception ignored) {
                // Logout is idempotent even when the cookie is expired or malformed.
            }
        }
        return ApiResponse.ok(Map.of("loggedOut", true));
    }
}
