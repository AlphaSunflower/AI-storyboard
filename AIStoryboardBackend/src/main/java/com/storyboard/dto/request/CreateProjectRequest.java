package com.storyboard.dto.request;

public record CreateProjectRequest(
    String name,
    String description,
    String creationType,
    String aspectRatio
) {}
