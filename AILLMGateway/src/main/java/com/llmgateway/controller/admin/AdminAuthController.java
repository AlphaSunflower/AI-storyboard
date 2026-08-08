package com.llmgateway.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambdaworks.crypto.SCryptUtil;
import com.llmgateway.dto.ApiResponse;
import com.llmgateway.dto.admin.AdminLoginRequest;
import com.llmgateway.dto.admin.AdminLoginResponse;
import com.llmgateway.entity.AdminUser;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.AdminUserMapper;
import com.llmgateway.security.JwtTokenProvider;
import org.springframework.web.bind.annotation.*;

/** 管理后台认证：登录发 JWT（access+refresh） */
@RestController
@RequestMapping("/admin")
public class AdminAuthController {

    private final AdminUserMapper adminUserMapper;
    private final JwtTokenProvider tokenProvider;

    public AdminAuthController(AdminUserMapper adminUserMapper, JwtTokenProvider tokenProvider) {
        this.adminUserMapper = adminUserMapper;
        this.tokenProvider = tokenProvider;
    }

    @PostMapping("/login")
    public ApiResponse<AdminLoginResponse> login(@RequestBody AdminLoginRequest request) {
        if (request.getUsername() == null || request.getPassword() == null) {
            throw new BusinessException(40001, "用户名和密码不能为空");
        }
        AdminUser user = adminUserMapper.selectOne(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getUsername, request.getUsername()));
        if (user == null || !SCryptUtil.check(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(40101, "用户名或密码错误");
        }
        if (!"enabled".equals(user.getStatus())) {
            throw new BusinessException(40301, "账号已禁用");
        }
        String access = tokenProvider.createAccessToken(user.getUsername(), user.getRole());
        String refresh = tokenProvider.createRefreshToken(user.getUsername());
        return ApiResponse.ok(new AdminLoginResponse(access, refresh));
    }
}
