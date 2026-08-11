package com.llmgateway.service;

import com.llmgateway.dto.admin.AdminLoginRequest;
import com.llmgateway.dto.admin.AdminLoginResponse;
import com.llmgateway.dto.admin.AdminUserRequest;
import com.llmgateway.entity.AdminUser;

import java.util.List;

/** 管理后台用户服务：登录认证 + 用户 CRUD（scrypt 哈希、防自锁保护） */
public interface AdminUserService {

    /** 登录：校验用户名密码（scrypt）、账号状态，签发 access+refresh JWT */
    AdminLoginResponse login(AdminLoginRequest request);

    /** 用户列表（按 createdAt 升序，passwordHash 已抹除） */
    List<AdminUser> list();

    /** 创建用户：用户名查重，scrypt 哈希，默认 role=admin/status=enabled；返回实体 passwordHash 已抹除 */
    AdminUser create(AdminUserRequest request);

    /** 更新用户：不能操作当前登录账号；禁用 admin 前校验剩余启用管理员 ≥ 1；password 非空才重置；status 校验枚举 */
    AdminUser update(String id, AdminUserRequest request);

    /** 删除用户：不能操作当前登录账号；删除启用 admin 前校验剩余启用管理员 ≥ 1 */
    void delete(String id);

    /** 用户总数 */
    long countAll();
}
