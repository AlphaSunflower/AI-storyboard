package com.llmgateway.service;

import com.llmgateway.dto.admin.RouteRequest;
import com.llmgateway.entity.ModelRoute;

import java.util.List;

/** 模型路由管理服务：模型名 → 渠道映射 CRUD */
public interface ModelRouteService {

    /** 创建路由：modelName + channelId 必填，渠道必须存在，type 默认 text 并校验枚举 */
    ModelRoute create(RouteRequest request);

    /** 路由列表（按 modelName 升序） */
    List<ModelRoute> list();

    /** 更新路由：换渠道时校验渠道存在；type 校验枚举 */
    ModelRoute update(String id, RouteRequest request);

    /** 删除路由（不存在抛 40401） */
    void delete(String id);

    /** 按 id 取路由（不存在抛 40401） */
    ModelRoute getById(String id);

    /** 某渠道下的全部路由（测试弹窗候选用） */
    List<ModelRoute> listByChannelId(String channelId);

    /** 路由总数 */
    long countAll();

    /**
     * 把某模型设为 / 取消该类型的默认路由：写 model_route 默认标记、同步 Redis
     * {@code gateway:default_model:<type>} 并清除模型列表缓存。
     *
     * <p>「默认模型」只有这一处权威来源（运行时时实际按它选模型）；模型参数页的
     * 「默认」开关也走这里，避免两处标记各自为政。
     *
     * @return 是否存在该模型在该类型下的路由（false = 没有可设默认的路由，仅记录参数表标记）
     */
    boolean setDefaultRoute(String type, String modelName, boolean isDefault);
}
