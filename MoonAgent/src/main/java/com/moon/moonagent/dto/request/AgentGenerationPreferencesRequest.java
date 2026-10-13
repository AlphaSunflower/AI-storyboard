package com.moon.moonagent.dto.request;

import java.util.Map;

/**
 * 客户端「生成偏好」请求体：自动（智能体决定）或自定义的比例 / 清晰度 / 模型，
 * 外加独立的「忽略对话中的生成方案」开关。
 *
 * <p>自动且不忽略方案时客户端整体不带该字段（null）→ 行为与改造前一致。
 */
public record AgentGenerationPreferencesRequest(
        Boolean auto,
        Boolean ignoreGenerationPlan,
        Map<String, String> image,
        Map<String, String> video
) {
}
