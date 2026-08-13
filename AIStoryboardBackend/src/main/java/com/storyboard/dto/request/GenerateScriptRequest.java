package com.storyboard.dto.request;

import java.util.List;

public record GenerateScriptRequest(
    String projectId,
    String scriptText,
    String creationType,
    String customTypeDesc,
    String aspectRatio,
    String model,
    String understandingModel,
    List<String> referenceImages
) {}
