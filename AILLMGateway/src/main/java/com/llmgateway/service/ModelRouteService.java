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
}
