package com.storyboard.dto.request;

import java.util.List;

/**
 * Dify Agent 图片生成请求
 *
 * @param mode              生图模式："generate"（图生图，默认）或 "edit"（图改图）
 * @param generatedImageUrl 完善图片时传入的已有图片 URL（仅 edit 模式使用，作为源图）
 */
public record DifyGenerateImageRequest(
    String projectId,
    String sceneId,
    String prompt,
    String model,
    String size,
    String quality,
    String mode,
    String generatedImageUrl,
    List<String> referenceImageUrls
) {}
