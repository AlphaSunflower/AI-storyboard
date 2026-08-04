package com.storyboard.dto.request;

/** 发送 Agent 对话消息请求（streaming 时 picUrl 为参考图 URL，来自 /api/agent/upload） */
public record AgentSendMessageRequest(
    String content,
    String picUrl
) {}
