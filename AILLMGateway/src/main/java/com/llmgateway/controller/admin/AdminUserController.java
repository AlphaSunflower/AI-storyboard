package com.llmgateway.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambdaworks.crypto.SCryptUtil;
import com.llmgateway.dto.ApiResponse;
import com.llmgateway.entity.AdminUser;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.AdminUserMapper;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 管理后台用户管理：scrypt 哈希（与 AdminInitRunner 一致 N=16384），列表/响应永不返回 passwordHash。
 * 防自锁双校验：不能操作当前登录账号；禁用/删除 admin 角色用户前校验剩余启用管理员 ≥ 1。
 */
@RestController
@RequestMapping("/admin/users")
public class AdminUserController {

    /** 创建/更新请求体：POST 用 username+password；PUT 用 password（非空才重置）+ status（enabled/disabled） */
    public record UserRequest(String username, String password, String status) {}

    private final AdminUserMapper adminUserMapper;

    public AdminUserController(AdminUserMapper adminUserMapper) {
        this.adminUserMapper = adminUserMapper;
    }

    @GetMapping
    public ApiResponse<List<AdminUser>> list() {
        List<AdminUser> users = adminUserMapper.selectList(new LambdaQueryWrapper<AdminUser>()
                .orderByAsc(AdminUser::getCreatedAt));
        users.forEach(u -> u.setPasswordHash(null));   // 抹掉哈希，保证不泄漏
        return ApiResponse.ok(users);
    }

    @PostMapping
    public ApiResponse<AdminUser> create(@RequestBody UserRequest request) {
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
        return ApiResponse.ok(user);
    }

    @PutMapping("/{id}")
    public ApiResponse<AdminUser> update(@PathVariable String id, @RequestBody UserRequest request) {
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
            user.setStatus(request.status());
        }
        user.setUpdatedAt(OffsetDateTime.now());
        adminUserMapper.updateById(user);
        user.setPasswordHash(null);
        return ApiResponse.ok(user);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
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
        return ApiResponse.ok(null);
    }
}
