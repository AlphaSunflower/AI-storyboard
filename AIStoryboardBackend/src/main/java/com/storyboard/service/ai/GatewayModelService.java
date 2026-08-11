package com.storyboard.service.ai;

import java.util.List;
import java.util.Map;

/**
 * LLM 网关模型列表服务 —— 从网关拉取模型列表供前端模型下拉动态展示。
 *
 * <p>GET {gateway}/v1/models?type=X → data[].id（网关按路由 type 过滤）；
 * 网关不可用/解析失败返回空列表（不阻塞主流程，前端回退硬编码默认列表）。
 */
public interface GatewayModelService {

    /**
     * 拉取指定类型的模型列表。
     *
     * @param type 路由类型（image / video）
     * @return 模型列表 [{value, label, params?}]；网关不可达或解析失败返回空列表
     */
    List<Map<String, String>> fetchModels(String type);
}
