package com.llmgateway.dto.admin;

import lombok.Data;

/** 管理后台登录请求 */
@Data
public class AdminLoginRequest {
    private String username;
    private String password;
}
