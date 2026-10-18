package com.moon.moonagent.dto.request;

/** 个人信息页编辑一条长期记忆。 */
public record AgentMemoryUpdateRequest(
        String kind,
        String content,
        Double confidence,
        String expiresAt) {
}
