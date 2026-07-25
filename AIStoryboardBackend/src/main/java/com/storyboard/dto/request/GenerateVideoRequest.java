package com.storyboard.dto.request;

import java.util.List;

public record GenerateVideoRequest(
    String sceneId,
    String prompt,
    String model,
    String resolution,
    Integer duration,
    List<String> referenceImages,
    String generatedImageUrl    // 新增：已生成的图片URL
) {}
