package com.storyboard.dto.request;

/** 发送 Agent 对话消息请求 */
public record AgentSendMessageRequest(
    String content
) {}
