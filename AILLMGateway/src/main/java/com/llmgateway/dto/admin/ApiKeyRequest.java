package com.llmgateway.dto.admin;

import lombok.Data;

/** 业务调用 Key 请求（POST 用 name 签发；PUT 用 enabled 开关） */
@Data
public class ApiKeyRequest {
    private String name;
    private Boolean enabled;
}
