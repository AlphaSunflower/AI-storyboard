package com.moon.moonagent.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 会话更新请求（重命名 / 归档），字段均可选 */
public record AgentConversationUpdateRequest(
    @Size(max = 200, message = "标题最长 200 字") String title,
    @Pattern(regexp = "active|archived", message = "状态必须是 active 或 archived") String status
) {}
