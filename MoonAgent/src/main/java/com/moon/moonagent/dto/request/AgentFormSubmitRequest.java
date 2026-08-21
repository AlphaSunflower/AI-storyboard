package com.moon.moonagent.dto.request;

import java.util.Map;

/** HITL 人工确认表单提交请求 */
public record AgentFormSubmitRequest(
    String formToken,   // Dify human_input_required 事件返回的表单令牌
    String taskId,      // Dify workflow_run_id（用于续流 /v1/workflow/{taskId}/events）
    String action,      // 用户点击的按钮 id（actions[].id；custom=自定义输入）
    String content,     // 自定义输入文本（action=custom 时必填，其余为空）
    Map<String, String> params,  // 卡片参数选择器提交的生成参数（如 {model, resolution, duration}；可空）
    java.util.List<String> assetIds,  // 资产选择卡片勾选的资产 ID 列表（可空）
    String routingHint  // 显式路由提示（可空）：前端「换个话题」等按钮主动切链时传目标 intentType（如 intent-pic），
                        // 路由优先级：routingHint > 卡片合法 action > customText 语义意图识别
) {}
