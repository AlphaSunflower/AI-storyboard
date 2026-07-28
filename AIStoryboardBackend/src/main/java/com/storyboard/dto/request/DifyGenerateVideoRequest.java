package com.storyboard.dto.request;

import java.util.List;

/**
 * Dify Agent 视频生成请求（后端代理 Laozhang 异步轮询）
 */
public record DifyGenerateVideoRequest(
    String projectId,
    String prompt,
    String model,
    String resolution,
    String size,
    String aspectRatio,
    int duration,
    String negativePrompt,
    List<String> referenceImageUrls
) {}
