package com.storyboard.dto.request;

import java.util.List;

public record GenerateVideoRequest(
    String sceneId,
    String prompt,
    String model,
    String resolution,
    Integer duration,
    List<String> referenceImages
) {}
