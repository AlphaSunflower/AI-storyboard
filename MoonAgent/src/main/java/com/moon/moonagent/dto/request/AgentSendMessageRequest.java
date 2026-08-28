package com.moon.moonagent.dto.request;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/** 发送 Agent 对话消息请求（streaming 时 picUrls 为参考图 URL 列表，来自 /api/agent/upload） */
public record AgentSendMessageRequest(
    @NotBlank(message = "消息内容不能为空") String content,
    String picUrl,          // 兼容保留（弃用；新客户端用 picUrls）
    List<String> picUrls    // 多图参考 URL 列表
) {}
