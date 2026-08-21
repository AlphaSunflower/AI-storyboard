package com.moon.moonagent.dto.request;

import jakarta.validation.constraints.NotBlank;

/** 发送 Agent 对话消息请求（streaming 时 picUrl 为参考图 URL，来自 /api/agent/upload） */
public record AgentSendMessageRequest(
    @NotBlank(message = "消息内容不能为空") String content,
    String picUrl
) {}
