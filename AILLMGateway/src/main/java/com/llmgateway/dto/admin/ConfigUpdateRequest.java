package com.llmgateway.dto.admin;

import java.util.List;

/** 系统可调配置批量更新请求：PUT /admin/config body */
public record ConfigUpdateRequest(List<ConfigItem> items) {

    /** 单个配置项：key=配置键（须为已知键），value=新值（字符串，服务端按键解析校验） */
    public record ConfigItem(String key, String value) {}
}
