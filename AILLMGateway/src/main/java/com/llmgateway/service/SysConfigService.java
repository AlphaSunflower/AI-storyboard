package com.llmgateway.service;

import com.llmgateway.dto.admin.ConfigUpdateRequest;
import com.llmgateway.entity.SysConfig;

import java.util.List;

/** 系统可调配置服务：查询全量 / 批量更新（校验 + upsert）；启动时把 DB 值加载进 GatewayConfig */
public interface SysConfigService {

    /** 查询全部配置（admin 表单回显；DB 无行则返回空列表） */
    List<SysConfig> getAll();

    /** 批量更新：逐项校验键合法性与值类型/范围后 upsert；返回更新后的全量 */
    List<SysConfig> updateValues(ConfigUpdateRequest request);
}
