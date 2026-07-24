package com.storyboard.service;

import com.storyboard.dto.request.LoginRequest;
import com.storyboard.dto.request.RegisterRequest;
import com.storyboard.dto.response.LoginResponse;
import com.storyboard.entity.User;
import com.storyboard.exception.BusinessException;
import com.storyboard.mapper.UserMapper;
import com.storyboard.security.JwtTokenProvider;
import com.storyboard.security.ScryptPasswordService;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
public class AuthService {

    private final UserMapper userMapper;
    private final JwtTokenProvider jwtTokenProvider;
    private final ScryptPasswordService passwordService;

    public AuthService(UserMapper userMapper, JwtTokenProvider jwtTokenProvider, ScryptPasswordService passwordService) {
        this.userMapper = userMapper;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordService = passwordService;
    }

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
}
