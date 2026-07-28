package com.storyboard.dto.request;

import java.util.List;

/**
 * Dify Agent 分镜脚本生成请求
 */
public record DifyGenerateScriptRequest(
    String projectId,
    List<SceneItem> scenes,
    String aspectRatio
) {
    public record SceneItem(
        int sceneNumber,
        String scriptContent,
        String imagePrompt,
        String videoPrompt,
        String negativePrompt,
        String cameraMovement,
        String shotType,
        String soundDesign
    ) {}
}
