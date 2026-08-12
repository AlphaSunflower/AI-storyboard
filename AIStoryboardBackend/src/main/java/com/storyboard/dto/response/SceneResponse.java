package com.storyboard.dto.response;

import java.time.OffsetDateTime;

public record SceneResponse(
    String id,
    String projectId,
    Integer sceneNumber,
    String scriptContent,
    String imagePrompt,
    String videoPrompt,
    String negativePrompt,
    String cameraMovement,
    String shotType,
    String soundDesign,
    String aiModel,
    String videoResolution,
    Integer duration,
    String imageUrl,
    String videoUrl,
    String imageStatus,
    String videoStatus,
    String imageUrls,
    String imageModel,
    String imageSize,
    String imageQuality,
    Integer imageN,
    String videoModel,
    String videoAspectRatio,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {}
