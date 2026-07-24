package com.storyboard.dto.request;

public record GenerateScriptRequest(
    String projectId,
    String scriptText,
    String creationType,
    String customTypeDesc,
    String aspectRatio,
    String model,
    String referenceImageUrl
) {}
