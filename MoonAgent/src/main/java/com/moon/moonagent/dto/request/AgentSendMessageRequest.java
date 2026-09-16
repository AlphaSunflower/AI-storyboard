package com.moon.moonagent.dto.request;

import com.moon.moonagent.ai.agent.CanvasSessionContext;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/** 发送 Agent 对话消息请求（streaming 时 picUrls 为参考图 URL 列表，来自 /api/agent/upload；assetIds 为选中的资产库资产 id） */
public record AgentSendMessageRequest(
    @NotBlank(message = "消息内容不能为空") String content,
    String picUrl,          // 兼容保留（弃用；新客户端用 picUrls）
    List<String> picUrls,   // 多图参考 URL 列表
    List<String> assetIds,  // 从资产库选中的资产 id（非空=用户已投资产，编排直接进方案不走资产弹窗）
    List<String> assetGroupIds, // 项目库资产组快照对应的组 id（仅视频链）
    String routingHint,     // 显式路由提示（可空）：非空时绕过意图模型直接锁定目标意图（如 intent-scene-review）
    Boolean cancelActiveTasks, // 可空：true=本轮新消息前取消该会话进行中的异步任务（如视频）
    CanvasSessionContext canvasContext // 可空：画布编辑模式由前端持续携带，直到用户显式离开
) {}
