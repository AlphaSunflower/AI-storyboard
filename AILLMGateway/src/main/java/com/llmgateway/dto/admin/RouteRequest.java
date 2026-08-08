package com.llmgateway.dto.admin;

import lombok.Data;

/** 模型路由创建/更新请求 */
@Data
public class RouteRequest {
    private String modelName;
    private String channelId;
    /** 模型类型：text/image/video/vision，为空默认 text */
    private String type;
    /** JSON：size/temperature 等默认参数，可选 */
    private String defaultParams;
}
