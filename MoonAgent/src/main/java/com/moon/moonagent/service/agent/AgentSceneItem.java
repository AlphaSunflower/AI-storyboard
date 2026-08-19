package com.moon.moonagent.service.agent;

/**
 * Agent 编排分镜条目（agent 自有类型，替代 DifyGenerateScriptRequest.SceneItem）。
 *
 * 8 字段对齐原 Dify SceneItem / scenes 表结构；由编排 LLM 结构化输出直接解析，
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
        String soundDesign
) {
}
