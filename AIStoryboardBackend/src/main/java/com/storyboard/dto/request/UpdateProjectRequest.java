package com.storyboard.dto.request;

public record UpdateProjectRequest(
    String name,
    String description,
    String scriptText,
    String creationType,
    String customTypeDesc,
    String aspectRatio,
    String referenceImageUrl,
    String aiModel,
    String status
) {}
