package com.moon.moonagent.dto.request;

/** 保存账号级全局记忆（单条，覆盖写）。 */
public record AgentGlobalMemoryRequest(String content) {
}
