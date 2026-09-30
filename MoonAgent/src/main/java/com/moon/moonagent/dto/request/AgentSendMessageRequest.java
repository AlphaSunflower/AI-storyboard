package com.moon.moonagent.dto.request;

import com.moon.moonagent.ai.agent.CanvasSessionContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import java.util.List;

/** 发送 Agent 对话消息请求（streaming 时 picUrls 为参考图 URL 列表，来自 /api/agent/upload；assetIds 为选中的资产库资产 id） */
public record AgentSendMessageRequest(
    @NotBlank(message = "消息内容不能为空")
    @Size(max = 32768, message = "消息内容过长")
    String content,
    @NotBlank(message = "clientRequestId 不能为空")
    @Size(max = 128, message = "幂等键过长") String clientRequestId,
    @Size(max = 2048, message = "参考图片 URL 过长") String picUrl, // 兼容保留（弃用；新客户端用 picUrls）
    @Size(max = 10, message = "参考图片最多 10 张") List<@Size(max = 2048, message = "参考图片 URL 过长") String> picUrls,
    @Size(max = 3, message = "参考视频最多 3 个") List<String> referenceVideos,
    @Size(max = 3, message = "参考音频最多 3 个") List<String> referenceAudios,
    @Size(max = 20, message = "附件最多 20 个") List<@Valid AgentAttachmentRequest> attachments,
    @Size(max = 20, message = "资产最多选择 20 个") List<String> assetIds,
    @Size(max = 10, message = "资产组最多 10 个") List<String> assetGroupIds,
    @Size(max = 10, message = "资产组快照最多 10 个") List<String> assetGroupSnapshotIds,
    String routingHint,     // 显式路由提示（可空）：非空时绕过意图模型直接锁定目标意图（如 intent-storyboard）
    Boolean cancelActiveTasks, // 可空：true=本轮新消息前取消该会话进行中的异步任务（如视频）
    String editMessageId,   // 可空：编辑最后一条用户消息并覆盖重发
    CanvasSessionContext canvasContext // 可空：画布编辑模式由前端持续携带，直到用户显式离开
) {}
