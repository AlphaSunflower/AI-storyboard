package com.moon.moonagent.service.ai;

import java.util.List;
import java.util.Map;

/**
 * LLM 网关模型列表服务 —— 从网关拉取模型列表供前端模型下拉动态展示，
 * 并缓存「默认模型」（网关 model_params.is_default 标记，单一权威源）。
 *
 * <p>GET {gateway}/v1/models?type=X → data[].id（网关按路由 type 过滤）；
 * 网关不可用/解析失败返回空列表（不阻塞主流程，前端回退硬编码默认列表）。
 */
public interface GatewayModelService {

    /**
     * 拉取指定类型的模型列表。
     *
     * @param type 路由类型（image / video / vision / text）
     * @return 模型列表 [{value, label, default?, params?}]；网关不可达或解析失败返回空列表
     */
    List<Map<String, String>> fetchModels(String type);

    /** 默认生图模型（图改图/文生图共用兜底；权威源网关 model_params.is_default，离线回退常量） */
    String getDefaultImageModel();

    /** 默认视频模型（兜底；权威源网关 model_params.is_default） */
    String getDefaultVideoModel();

    /** 默认视觉/理解模型（脚本生成/看图/优化共用兜底；权威源网关 model_params.is_default） */
    String getDefaultVisionModel();

    /** 默认文本/对话模型（Agent 编排意图识别/标题/主回答/优化/资产判定共用；权威源网关 model_params.is_default） */
    String getDefaultTextModel();
}
