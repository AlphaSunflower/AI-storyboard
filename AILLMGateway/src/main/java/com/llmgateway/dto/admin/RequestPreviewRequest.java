package com.llmgateway.dto.admin;

/** 请求预览：不调用上游，只展示模型 Spec + 路由覆盖后的最终请求。 */
public record RequestPreviewRequest(String modelName, String type, String channelId,
                                    String path, String body) {
}
