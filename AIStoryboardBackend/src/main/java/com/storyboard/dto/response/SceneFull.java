package com.storyboard.dto.response;

import java.time.OffsetDateTime;

/**
 * 分镜全量 DTO（基础列 + prompt 明细列）。仅供内部 /api/internal/** 契约使用，
 * 与拆分前 {@code Scene} 实体的 JSON 形状一致（含 imageTaskId），保证 MoonAgent 无需改动。
 */
public record SceneFull(
    String id,
    String projectId,
    String conversationId,
    Integer sceneNumber,
    String scriptContent,
    String imagePrompt,
    String videoPrompt,
    String negativePrompt,
    /** 本镜与前镜的衔接说明（继承项/禁改项），生成时拼进提示词 */
    String continuityNote,
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
) {

    /**
     * 向后兼容构造器：省略 {@code continuityNote}（等价于空串）。
     * 内部 API 的老调用点与测试无需因为新增衔接说明而逐个改参数表。
     */
    public SceneFull(
        String id, String projectId, String conversationId, Integer sceneNumber,
        String scriptContent, String imagePrompt, String videoPrompt, String negativePrompt,
        String cameraMovement, String shotType, String soundDesign, String aiModel,
        String videoResolution, Integer duration, String imageUrl, String videoUrl,
        String imageStatus, String videoStatus, String imageTaskId, String videoTaskId,
        String imageUrls, String imageModel, String imageSize, String imageQuality,
        Integer imageN, String videoModel, String videoAspectRatio,
        OffsetDateTime createdAt, OffsetDateTime updatedAt
    ) {
        this(id, projectId, conversationId, sceneNumber, scriptContent, imagePrompt, videoPrompt,
             negativePrompt, "", cameraMovement, shotType, soundDesign, aiModel, videoResolution,
             duration, imageUrl, videoUrl, imageStatus, videoStatus, imageTaskId, videoTaskId,
             imageUrls, imageModel, imageSize, imageQuality, imageN, videoModel, videoAspectRatio,
             createdAt, updatedAt);
    }
}
