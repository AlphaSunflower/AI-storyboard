package com.storyboard.dto.request;

/**
 * 分镜关联的产出素材或当前分镜图片引用。
 *
 * @param type                 素材类型 image | video
 * @param purpose              用途 image | video
 * @param url                  素材 URL
 * @param role                 参考角色，默认 REFERENCE
 * @param sourceType           来源 output | scene_image
 * @param sourceId             产出记录 ID 或分镜 ID
 * @param sourceConversationId 产出素材所属会话 ID
 */
public record SceneMaterialReferenceRequest(
        String type,
        String purpose,
        String url,
        String role,
        String sourceType,
        String sourceId,
        String sourceConversationId
) {}
