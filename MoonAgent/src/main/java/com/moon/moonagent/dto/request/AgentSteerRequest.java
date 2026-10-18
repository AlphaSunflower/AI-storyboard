package com.moon.moonagent.dto.request;

/**
 * 回合中插入一条用户消息（steer）的请求。
 *
 * @param content         正文
 * @param clientMessageId 客户端生成的幂等 ID，用于前端乐观气泡与服务端事件去重
 */
public record AgentSteerRequest(String content, String clientMessageId) {
}
