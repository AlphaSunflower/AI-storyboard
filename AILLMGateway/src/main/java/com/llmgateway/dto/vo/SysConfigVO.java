package com.llmgateway.dto.vo;

import java.time.OffsetDateTime;

/** 系统可调配置视图对象（admin 表单回显） */
public record SysConfigVO(String key, String value, String remark, OffsetDateTime updatedAt) {}
