package com.llmgateway.controller.admin;

import com.llmgateway.dto.ApiResponse;
import com.llmgateway.dto.admin.AdminUserRequest;
import com.llmgateway.dto.vo.AdminUserVO;
import com.llmgateway.entity.AdminUser;
import com.llmgateway.service.AdminUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 管理后台用户管理：scrypt 哈希（与 AdminInitRunner 一致 N=16384），列表/响应永不返回 passwordHash。
 * 防自锁双校验（不能操作当前登录账号；禁用/删除 admin 角色用户前校验剩余启用管理员 ≥ 1）在 AdminUserService 内实现。
 */
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    @GetMapping
    public ApiResponse<List<AdminUserVO>> list() {
        return ApiResponse.ok(adminUserService.list().stream().map(this::toVO).toList());
    }

    @PostMapping
    public ApiResponse<AdminUserVO> create(@RequestBody AdminUserRequest request) {
        return ApiResponse.ok(toVO(adminUserService.create(request)));
    }

    @PutMapping("/{id}")
    public ApiResponse<AdminUserVO> update(@PathVariable String id, @RequestBody AdminUserRequest request) {
        return ApiResponse.ok(toVO(adminUserService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        adminUserService.delete(id);
        return ApiResponse.ok(null);
    }

    private AdminUserVO toVO(AdminUser e) {
        return new AdminUserVO(e.getId(), e.getUsername(), e.getRole(), e.getStatus(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
