package com.storyboard.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

public record ProjectResponse(
    String id,
    String userId,
    String name,
    String description,
    String creationType,
    String customTypeDesc,
    String aspectRatio,
    String referenceImageUrl,
    String scriptText,
    String aiModel,
    String status,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    List<SceneResponse> scenes
) {}
