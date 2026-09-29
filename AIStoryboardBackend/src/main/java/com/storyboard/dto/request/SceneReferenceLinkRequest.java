package com.storyboard.dto.request;

/**
 * 内部挂载分镜参考素材：Agent 生成分镜后把已上传图片/资产图自动关联到对应分镜。
 */
public record SceneReferenceLinkRequest(
        String url,
        String type,
        String purpose,
        String role,
        String fileName,
        Long fileSize,
        String sourceType,
        String sourceId,
        String sourceConversationId
) {}
