package com.llmgateway.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambdaworks.crypto.SCryptUtil;
import com.llmgateway.dto.admin.AdminLoginRequest;
import com.llmgateway.dto.admin.AdminLoginResponse;
import com.llmgateway.dto.admin.AdminUserRequest;
import com.llmgateway.entity.AdminUser;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.AdminUserMapper;
import com.llmgateway.security.JwtTokenProvider;
import com.llmgateway.service.AdminUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * 管理后台用户服务实现：scrypt 哈希（与 AdminInitRunner 一致 N=16384），列表/响应永不返回 passwordHash。
 * 防自锁双校验：不能操作当前登录账号；禁用/删除 admin 角色用户前校验剩余启用管理员 ≥ 1。
 */
@Service
@RequiredArgsConstructor
public class AdminUserServiceImpl implements AdminUserService {

    private final AdminUserMapper adminUserMapper;
    private final JwtTokenProvider tokenProvider;

    /** 登录：校验用户名密码（scrypt）、账号状态，签发 access+refresh JWT */
    @Override
    public AdminLoginResponse login(AdminLoginRequest request) {
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
        return new AdminLoginResponse(access, refresh);
    }

    @Override
    public List<AdminUser> list() {
        List<AdminUser> users = adminUserMapper.selectList(new LambdaQueryWrapper<AdminUser>()
                .orderByAsc(AdminUser::getCreatedAt));
        users.forEach(u -> u.setPasswordHash(null));   // 抹掉哈希，保证不泄漏
        return users;
    }

    @Override
    public AdminUser create(AdminUserRequest request) {
        if (request.username() == null || request.username().isBlank()
                || request.password() == null || request.password().isBlank()) {
            throw new BusinessException(40001, "用户名和密码不能为空");
        }
        String username = request.username().trim();
        Long dup = adminUserMapper.selectCount(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getUsername, username));
        if (dup != null && dup > 0) {
            throw new BusinessException(40001, "用户名已存在");
        }
        AdminUser user = new AdminUser();
        user.setUsername(username);
        user.setPasswordHash(SCryptUtil.scrypt(request.password(), 16384, 8, 1));
        user.setRole("admin");
        user.setStatus("enabled");
        user.setCreatedAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        adminUserMapper.insert(user);
        user.setPasswordHash(null);
        return user;
    }

    @Override
    public AdminUser update(String id, AdminUserRequest request) {
        AdminUser user = adminUserMapper.selectById(id);
        if (user == null) throw new BusinessException(40401, "用户不存在");
        String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();
        if (user.getUsername().equals(currentUsername)) {
            throw new BusinessException(40301, "不能操作当前登录账号");
        }
        // 防自锁：把启用的 admin 改为 disabled 前，校验剩余启用管理员 ≥ 1
        if ("disabled".equals(request.status()) && "admin".equals(user.getRole()) && "enabled".equals(user.getStatus())) {
            Long enabledAdmins = adminUserMapper.selectCount(new LambdaQueryWrapper<AdminUser>()
                    .eq(AdminUser::getRole, "admin")
                    .eq(AdminUser::getStatus, "enabled"));
            if (enabledAdmins != null && enabledAdmins <= 1) {
                throw new BusinessException(40301, "必须至少保留一个启用的管理员");
            }
        }
        if (request.password() != null && !request.password().isBlank()) {
            user.setPasswordHash(SCryptUtil.scrypt(request.password(), 16384, 8, 1));
        }
        if (request.status() != null) {
            // 校验 status 枚举：脏值会破坏登录判定（"enabled".equals 永假）与最后管理员保护计数
            if (!Set.of("enabled", "disabled").contains(request.status())) {
                throw new BusinessException(40001, "status 仅支持 enabled/disabled");
            }
            user.setStatus(request.status());
        }
        user.setUpdatedAt(OffsetDateTime.now());
        adminUserMapper.updateById(user);
        user.setPasswordHash(null);
        return user;
    }

    @Override
    public void delete(String id) {
        AdminUser user = adminUserMapper.selectById(id);
        if (user == null) throw new BusinessException(40401, "用户不存在");
        String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();
        if (user.getUsername().equals(currentUsername)) {
            throw new BusinessException(40301, "不能操作当前登录账号");
        }
        // 防自锁：删除启用的 admin 前，校验剩余启用管理员 ≥ 1（已禁用的 admin 删除不影响启用数，无需拦截）
        if ("admin".equals(user.getRole()) && "enabled".equals(user.getStatus())) {
            Long enabledAdmins = adminUserMapper.selectCount(new LambdaQueryWrapper<AdminUser>()
                    .eq(AdminUser::getRole, "admin")
                    .eq(AdminUser::getStatus, "enabled"));
            if (enabledAdmins != null && enabledAdmins <= 1) {
                throw new BusinessException(40301, "必须至少保留一个启用的管理员");
            }
        }
        adminUserMapper.deleteById(id);
    }

    @Override
    public long countAll() {
        Long count = adminUserMapper.selectCount(null);
        return count == null ? 0 : count;
    }
}
