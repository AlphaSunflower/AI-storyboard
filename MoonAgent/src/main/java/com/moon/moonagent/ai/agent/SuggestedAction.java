package com.moon.moonagent.ai.agent;

/**
 * 跟随 assistant 消息的推荐动作按钮（如「审查分镜」）。
 *
 * @param id          动作 id（如 scene-review）
 * @param title       按钮文案（如「审查分镜」）
 * @param routingHint 点击后传递给后端的路由提示（如 intent-scene-review）
 */
public record SuggestedAction(
    String id,
    String title,
    String routingHint
) {}
