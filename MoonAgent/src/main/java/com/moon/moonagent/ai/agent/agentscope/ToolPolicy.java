package com.moon.moonagent.ai.agent.agentscope;

/**
 * 工具审计/审批策略。
 *
 * @param toolKind   审计分类（READ / WRITE / MEDIA_IMAGE / STORYBOARD_WRITE …）
 * @param actionType 外部动作类型（用于幂等账本；空串表示无外部副作用）
 * @param preflight  执行前是否需要媒体预检
 * @param readOnly   是否只读
 */
record ToolPolicy(
        String toolKind,
        String actionType,
        boolean preflight,
        boolean readOnly
) {
}
