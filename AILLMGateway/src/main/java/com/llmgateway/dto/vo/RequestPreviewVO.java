package com.llmgateway.dto.vo;

import java.util.List;

/**
 * 请求预览结果。original/afterSpec/forwarded 都是 JSON 字符串，前端格式化展示。
 */
public record RequestPreviewVO(String routeId,
                               String modelName,
                               String type,
                               String channelId,
                               String channelName,
                               String channelType,
                               String upstreamModel,
                               String targetUrl,
                               String note,
                               boolean specFound,
                               String original,
                               String afterSpec,
                               String forwarded,
                               List<String> steps) {
}
