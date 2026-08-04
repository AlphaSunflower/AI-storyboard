package com.storyboard.dto.request;

/** 会话更新请求（重命名 / 归档），字段均可选 */
public record AgentConversationUpdateRequest(
    String title,    // 非空则重命名
    String status    // active | archived，非空则归档/恢复
) {}
