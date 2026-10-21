package com.moon.moonagent.dto.response;

import com.moon.moonagent.ai.agent.memory.AgentMemoryLimits;

/**
 * 记忆管理状态：总开关 + 全局记忆 + 项目记忆容量。
 *
 * @param enabled 记忆管理是否启用；关闭后智能体不能写/召回，用户不能改全局记忆
 * @param global  当前账号的全局记忆；未设置时为 null
 * @param limits  项目记忆容量上限
 */
public record AgentMemorySettingsVO(
        boolean enabled,
        AgentGlobalMemoryVO global,
        AgentMemoryLimits limits) {
}
