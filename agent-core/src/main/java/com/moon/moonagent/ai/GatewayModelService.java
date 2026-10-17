package com.moon.moonagent.ai;

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

    /**
     * 最近一次模型列表拉取失败的原因（网关返回非 2xx / 连接失败 / 解析失败），
     * 供前端提示与排障；成功拉取或尚未尝试时返回 {@code null}。
     *
     * <p>模型列表拉取失败时接口会退化为空列表（不阻塞主流程），若没有这个原因，
     * 「网关没数据」与「网关不可达」在界面上无法区分。
     */
    default String lastModelFetchError(String type) {
        return null;
    }

    /**
     * 最近一次已知的网关模型目录版本号（网关 {@code gateway:models:version}）。
     *
     * <p>网关任何一次模型目录变更（路由 / 渠道 / 参数 / 默认模型）都会换一个新版本号；前端可据此
     * 判断「下拉里的模型信息是否真的变了」，避免轮询时做无意义的重渲染。
     * Redis 不可用且尚未从响应里看到过版本号时返回 {@code null}。
     */
    default String catalogVersion() {
        return null;
    }

    /**
     * 模型上下文窗口（token 数）：权威源为网关 {@code model_params.context_window}，
     * 经 {@code GET {gateway}/v1/models?type=text} 的 {@code data[].contextWindow} 透出。
     *
     * <p>网关不可达或该模型未配置时返回 {@code null}，由调用方回退自身默认窗口；实现应做短 TTL 缓存，
     * 避免每轮对话都触发一次模型列表请求。
     */
    default Integer resolveContextWindow(String modelName) {
        return null;
    }
}
