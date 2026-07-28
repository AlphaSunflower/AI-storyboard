package com.storyboard.dto.request;

import java.util.List;

/**
 * Dify Agent 图片生成请求
 */
public record DifyGenerateImageRequest(
    String projectId,
    String prompt,
    String model,
    String size,
    String quality,
    List<String> referenceImageUrls
) {}
