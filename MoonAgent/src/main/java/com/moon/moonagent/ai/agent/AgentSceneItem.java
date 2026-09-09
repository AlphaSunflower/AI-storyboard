package com.moon.moonagent.ai.agent;

/**
 * Agent 编排分镜条目（agent 自有类型，替代 GenerateScriptRequest.SceneItem）。
 *
 * 8 字段对齐原 SceneItem / scenes 表结构；由编排 LLM 结构化输出直接解析，
 * 经 AgentTools.writeScenes 复用 AgentGenerationService.writeScript 批量写库。
 */
public record AgentSceneItem(
        int sceneNumber,
        String scriptContent,
        String imagePrompt,
        String videoPrompt,
        String negativePrompt,
        String cameraMovement,
        String shotType,
        String soundDesign,
        Integer duration
) {
    public AgentSceneItem(int sceneNumber, String scriptContent, String imagePrompt,
                          String videoPrompt, String negativePrompt, String cameraMovement,
                          String shotType, String soundDesign) {
        this(sceneNumber, scriptContent, imagePrompt, videoPrompt, negativePrompt,
                cameraMovement, shotType, soundDesign, null);
    }
}
