package com.moon.moonagent.ai.agent.storyboard;

import java.util.List;

/**
 * 分镜方案设计师输出：单个分镜 + 该镜用到的素材绑定（含参考角色）。
 */
public record StoryboardScenePlan(
        int sceneNumber,
        String scriptContent,
        String imagePrompt,
        String videoPrompt,
        String negativePrompt,
        String cameraMovement,
        String shotType,
        String soundDesign,
        List<StoryboardReferenceBinding> referenceBindings
) {
    public StoryboardScenePlan {
        referenceBindings = referenceBindings == null ? List.of() : referenceBindings;
    }
}
