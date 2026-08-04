package com.storyboard.dto.request;

/** HITL 人工确认表单提交请求 */
public record AgentFormSubmitRequest(
    String formToken,   // Dify human_input_required 事件返回的表单令牌
    String taskId,      // Dify workflow_run_id（用于续流 /v1/workflow/{taskId}/events）
    String action       // 用户点击的按钮 id（actions[].id）
) {}
