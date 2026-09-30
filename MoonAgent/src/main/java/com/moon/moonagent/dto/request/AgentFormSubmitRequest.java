package com.moon.moonagent.dto.request;

import com.moon.moonagent.ai.agent.CanvasSessionContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** HITL 人工确认表单提交请求 */
public record AgentFormSubmitRequest(
    @NotBlank(message = "表单令牌不能为空") @Size(max = 128) String formToken,
    @Size(max = 128, message = "任务 ID 过长") String taskId,
    @NotBlank(message = "动作不能为空") @Size(max = 64, message = "动作 ID 过长") String action,
    @Size(max = 32768, message = "输入内容过长") String content,
    @Size(max = 50, message = "参数数量过多") Map<@Size(max = 64) String, @Size(max = 512) String> params,
    @Size(max = 100, message = "资产最多选择 100 个")
    java.util.List<@Size(max = 128) String> assetIds,
    @Size(max = 64, message = "路由提示过长") String routingHint,
                        // 路由优先级：routingHint > 卡片合法 action > customText 语义意图识别
    CanvasSessionContext canvasContext // 画布编辑模式上下文；HITL 续流必须原样带回
) {}
