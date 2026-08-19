package com.storyboard.service.impl;

import com.storyboard.dto.request.LoginRequest;
import com.storyboard.dto.request.RegisterRequest;
import com.storyboard.dto.request.UnloginRequest;
import com.storyboard.common.ApiResponse;
import com.storyboard.dto.response.LoginResponse;
import com.storyboard.entity.User;
import com.storyboard.common.BusinessException;
import com.storyboard.mapper.UserMapper;
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

    @Override
    public LoginResponse login(LoginRequest request) {
        User user = userMapper.findByEmail(request.email().toLowerCase().trim());
        if (user == null) {
            throw new BusinessException(40102, "用户名或密码错误");
        }
        if (!passwordService.verifyPassword(request.password(), user.getPasswordHash())) {
            throw new BusinessException(40102, "用户名或密码错误");
        }
        userMapper.updateLastLoginAt(user.getId(), OffsetDateTime.now());

        String accessToken = jwtTokenProvider.signAccessToken(user.getId(), user.getRole(), user.getStatus());
        String refreshToken = jwtTokenProvider.signRefreshToken(user.getId());

        return new LoginResponse(accessToken, refreshToken, user.getId(), user.getDisplayName());
    }

    @Override
    public LoginResponse register(RegisterRequest request) {
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

        String accessToken = jwtTokenProvider.signAccessToken(user.getId(), user.getRole(), user.getStatus());
        String refreshToken = jwtTokenProvider.signRefreshToken(user.getId());

        return new LoginResponse(accessToken, refreshToken, user.getId(), user.getDisplayName());
    }

    @Override
    public LoginResponse refresh(String refreshToken) {
        try {
            var claims = jwtTokenProvider.verifyRefreshToken(refreshToken);
            String userId = claims.getSubject();
            User user = userMapper.selectById(userId);
            if (user == null || "disabled".equals(user.getStatus())) {
                throw new BusinessException(40101, "用户不存在或已禁用");
            }
            String newAccessToken = jwtTokenProvider.signAccessToken(user.getId(), user.getRole(), user.getStatus());
            String newRefreshToken = jwtTokenProvider.signRefreshToken(user.getId());
            return new LoginResponse(newAccessToken, newRefreshToken, user.getId(), user.getDisplayName());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(40101, "Token 无效或已过期");
        }
    }

    @Override
    public ApiResponse<Map<String, Object>> handleUnlogin(UnloginRequest req, String authHeader) {
        String userIdFromHeader = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try { userIdFromHeader = jwtTokenProvider.tokenToUserId(authHeader.substring(7)); } catch (Exception ignored) {}
        }

        // 解析 JWT → userId
        String userIdFromJwt;
        try {
            userIdFromJwt = jwtTokenProvider.tokenToUserId(req.jwt());
        } catch (Exception e) {
            throw new BusinessException(40101, "JWT 无效");
        }

        // 查用户
        User user = userMapper.findByEmail(req.account());
        if (user == null) {
            throw new BusinessException(40101, "账号不存在");
        }

        // 验证 account 和 jwt 指向同一用户
        if (!user.getId().equals(userIdFromJwt)) {
            throw new BusinessException(40101, "账号与JWT不匹配");
        }

        // 已有 token 且同一用户
        if (userIdFromHeader != null && userIdFromHeader.equals(userIdFromJwt)) {
            return ApiResponse.ok(Map.of("alreadyLoggedIn", true, "userId", user.getId(), "displayName", user.getDisplayName()));
        }

        // 签发新 token
        String accessToken = jwtTokenProvider.signAccessToken(user.getId(), user.getRole(), user.getStatus());
        String refreshToken = jwtTokenProvider.signRefreshToken(user.getId());
        return ApiResponse.ok(Map.of("accessToken", accessToken, "refreshToken", refreshToken, "userId", user.getId(), "displayName", user.getDisplayName()));
    }
}
