package com.storyboard.dto.request;

import java.util.List;

/**
 * Dify Agent 图片生成请求
 *
 * @param conversationId      Agent 会话 ID（sceneId 为空时资产归属该会话；为空则未归属）
 * @param picUrl              用户上传的参考图 URL（图生图源图，优先于 generatedImageUrl）
 * @param generatedImageUrl   完善图片时传入的已有图片 URL（仅 edit 模式使用，作为源图）
 */
public record DifyGenerateImageRequest(
    String projectId,
    String sceneId,
    String prompt,
    String model,
    String size,
    String quality,
    String mode,
    String generatedImageUrl,
    List<String> referenceImageUrls,
    String conversationId,
    String picUrl
) {}
