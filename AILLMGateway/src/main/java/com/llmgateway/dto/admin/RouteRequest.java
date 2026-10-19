package com.llmgateway.dto.admin;

import lombok.Data;

import java.util.List;

/** 模型路由创建/更新请求 */
@Data
public class RouteRequest {
    private String modelName;
    private String channelId;
    /** 模型类型：text/image/video/vision，为空默认 text */
    private String type;
    /** JSON：size/temperature 等默认参数，可选（历史字段，暂未参与注入） */
    private String defaultParams;
    private Boolean isDefault;
    /** 路由级启停 */
    private Boolean enabled;
    /** 优先级（越小越先） */
    private Integer priority;
    /** 同优先级权重（与渠道权重相乘） */
    private Integer weight;
    /** 公共模型名 → 上游模型名；空=同名 */
    private String upstreamModel;
    /** 单请求超时覆盖（毫秒） */
    private Integer timeoutMs;
    /** 路由级额外重试次数 */
    private Integer maxRetries;
    /** 参数覆盖 JSON：{set:{},setIfAbsent:{},delete:[]} */
    private String paramOverrides;
    /** 该模型的跨模型回退链（按顺序）；null=不修改，[]=清空 */
    private List<String> fallbacks;
}
