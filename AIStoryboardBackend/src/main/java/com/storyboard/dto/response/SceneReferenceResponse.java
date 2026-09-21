package com.storyboard.dto.response;

/**
 * 分镜参考素材响应（scene_reference_images 表，type: image/video/audio）。
 */
public record SceneReferenceResponse(
    String id,
    String type,
    String purpose,
    String url,
    String role,
    String sourceType,
    String sourceId,
    String mediaId,
    String sourceConversationId,
    String fileName,
    Long fileSize
) {
    public SceneReferenceResponse(
            String id,
            String type,
            String purpose,
            String url,
            String role,
            String sourceType,
            String sourceId,
            String sourceConversationId,
            String fileName,
            Long fileSize
    ) {
        this(id, type, purpose, url, role, sourceType, sourceId, null, sourceConversationId, fileName, fileSize);
    }
}
