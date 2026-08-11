package com.llmgateway.dto.admin;

/** 管理后台用户创建/更新请求：POST 用 username+password；PUT 用 password（非空才重置）+ status（enabled/disabled） */
public record AdminUserRequest(String username, String password, String status) {}
