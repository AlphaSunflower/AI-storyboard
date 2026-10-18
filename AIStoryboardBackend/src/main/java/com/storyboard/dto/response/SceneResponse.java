package com.storyboard.dto.response;

import java.time.OffsetDateTime;

public record SceneResponse(
    String id,
    String projectId,
    String conversationId,
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
    String videoTaskId,
    String imageUrls,
    String imageModel,
    String imageSize,
    String imageQuality,
    Integer imageN,
    String videoModel,
    String videoAspectRatio,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    /**
     * 媒体是否已过期：画面/地点/资产在该镜出图或出视频之后又被改过。
     *
     * <p>读取时派生（比对改动日志时间戳），不落库——过期状态随时可重算，
     * 每次改一句大纲都去 UPDATE 一批行只会加重写入负担。
     */
    Boolean stale
) {}
