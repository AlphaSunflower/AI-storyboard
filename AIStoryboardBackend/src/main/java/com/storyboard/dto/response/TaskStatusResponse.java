package com.storyboard.dto.response;

public record TaskStatusResponse(
    String taskId,
    String status,
    String videoUrl,
    String progress,
    String error
) {}
