package com.storyboard.dto.response;

/**
 * 分镜参考素材响应（scene_reference_images 表，type: image/video/audio）。
 */
public record SceneReferenceResponse(
    String id,
    String type,
    String url,
    String fileName,
    Long fileSize
) {}
