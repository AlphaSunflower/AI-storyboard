package com.storyboard.service;

import com.storyboard.dto.request.LoginRequest;
import com.storyboard.dto.request.RegisterRequest;
import com.storyboard.dto.request.UnloginRequest;
import com.storyboard.common.ApiResponse;
import com.storyboard.dto.response.LoginResponse;

import java.util.Map;

/**
 * 认证服务接口：登录、注册、刷新 Token、未登录处理。
 */
public interface AuthService {

    /** 用户登录：按邮箱校验密码，签发 access/refresh token。 */
    LoginResponse login(LoginRequest request);

    /** 用户注册：校验邮箱唯一性，创建用户并签发 token。 */
    LoginResponse register(RegisterRequest request);

    /** 刷新令牌：校验 refreshToken 并签发新的一组 access/refresh token。 */
    LoginResponse refresh(String refreshToken);

    /** 未登录处理：解析 JWT 与账号，校验同一用户后签发新 token（已登录则原样返回）。 */
    ApiResponse<Map<String, Object>> handleUnlogin(UnloginRequest req, String authHeader);
}
