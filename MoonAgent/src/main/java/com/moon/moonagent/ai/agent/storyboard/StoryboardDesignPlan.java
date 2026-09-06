package com.moon.moonagent.ai.agent.storyboard;

import java.util.List;

/**
 * 分镜方案设计师整体输出：逐镜方案 + 全局画幅 + 方案说明。
 */
public record StoryboardDesignPlan(
        List<StoryboardScenePlan> scenes,
        String aspectRatio,
        String message
) {
    public StoryboardDesignPlan {
        scenes = scenes == null ? List.of() : scenes;
        aspectRatio = aspectRatio == null || aspectRatio.isBlank() ? "16:9" : aspectRatio;
        message = message == null ? "" : message;
    }
}
