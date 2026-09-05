package com.storyboard.dto.response;

import java.time.OffsetDateTime;

/**
 * 分镜全量 DTO（基础列 + prompt 明细列）。仅供内部 /api/internal/** 契约使用，
 * 与拆分前 {@code Scene} 实体的 JSON 形状一致（含 imageTaskId），保证 MoonAgent 无需改动。
 */
public record SceneFull(
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
    String imageTaskId,
    String videoTaskId,
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
