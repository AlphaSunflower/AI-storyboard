package com.storyboard.dto.request;

import java.util.List;

public record GenerateImageRequest(
    String sceneId,
    String prompt,
    String model,
    String size,
    String aspectRatio,
    List<String> referenceImages
) {}
