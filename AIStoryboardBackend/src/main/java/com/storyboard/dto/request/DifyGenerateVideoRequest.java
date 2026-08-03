package com.storyboard.dto.request;

import java.util.List;

/**
 * Dify Agent 视频生成请求（后端代理 Laozhang 异步轮询）
 *
 * @param conversationId  Agent 会话 ID（sceneId 为空时资产归属该会话；为空则未归属）
 * @param picUrl          用户上传的参考图 URL（图生视频源图）
 */
public record DifyGenerateVideoRequest(
    String projectId,
    String sceneId,
    String prompt,
    String model,
    String resolution,
    String size,
    String aspectRatio,
    String duration,
    String negativePrompt,
    List<String> referenceImageUrls,
    String conversationId,
    String picUrl
) {}
