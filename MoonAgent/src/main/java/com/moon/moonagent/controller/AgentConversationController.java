package com.moon.moonagent.controller;

import com.moon.moonagent.dto.request.AgentConversationUpdateRequest;
import com.moon.moonagent.dto.request.AgentCreateConversationRequest;
import com.moon.moonagent.dto.request.AgentFormSubmitRequest;
import com.moon.moonagent.dto.request.AgentSendMessageRequest;
import com.moon.moonagent.dto.request.AgentVideoPlanGenerateRequest;
import com.moon.moonagent.dto.request.PromptOptimizeRequest;
import com.moon.moonagent.dto.response.AgentConversationVO;
import com.moon.moonagent.dto.response.AgentMessageVO;
import com.moon.moonagent.dto.response.ProjectOutputMaterialGroup;
import com.storyboard.common.ApiResponse;
import com.storyboard.asset.client.AssetClient;
import com.storyboard.asset.client.AssetContracts;
import com.storyboard.asset.client.AssetContracts.AssetGroupSnapshot;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;
import com.storyboard.common.BusinessException;
import com.moon.moonagent.ai.agent.AgentChatService;
import com.moon.moonagent.ai.agent.GenerationRecordService;
import com.moon.moonagent.ai.agent.PromptOptimizeService;
import com.moon.moonagent.ai.AgentAiConfigProperties;
import com.moon.moonagent.service.ProjectOutputMaterialService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Agent 智能体对话端点：会话 / 消息 / 资产 / 图片上传 / 提示词优化。
 * 仅收参 → 校验 → 调 Service → 封装返回，不持有数据访问层与业务逻辑。
 * 鉴权由 Gateway 统一验签后透传 X-User-Id header（GatewayAuthenticationFilter 装配）。
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentConversationController {

    private static final Logger log = LoggerFactory.getLogger(AgentConversationController.class);

    private final AgentChatService chatService;
    private final PromptOptimizeService optimizeService;
    private final GenerationRecordService generationRecordService;
    private final ProjectOutputMaterialService projectOutputMaterialService;
    private final AgentAiConfigProperties agentConfig;
    private final AssetClient assetClient;

    /** 创建会话 */
    @PostMapping("/conversations")
    public ApiResponse<AgentConversationVO> createConversation(
            Authentication auth, @Valid @RequestBody AgentCreateConversationRequest request) {
        return ApiResponse.ok(toVO(chatService.createConversation(auth.getName(), request.projectId(), request.title())));
    }

    /** 当前用户的项目会话列表（updated_at 倒序） */
    @GetMapping("/conversations")
    public ApiResponse<List<AgentConversationVO>> listConversations(
            Authentication auth, @RequestParam String projectId) {
        return ApiResponse.ok(chatService.listConversations(auth.getName(), projectId).stream()
                .map(AgentConversationController::toVO).toList());
    }

    /** 会话详情（含消息列表） */
    @GetMapping("/conversations/{id}")
    public ApiResponse<Map<String, Object>> getConversation(
            Authentication auth, @PathVariable String id) {
        AgentConversation conversation = chatService.getOwnedConversation(auth.getName(), id);
        List<AgentMessageVO> messages = chatService.listMessages(id).stream()
                .map(AgentConversationController::toVO).toList();
        return ApiResponse.ok(Map.of("conversation", toVO(conversation), "messages", messages));
    }

    /** 获取会话的待处理 checkpoint（页面刷新恢复 HITL 卡片用） */
    @GetMapping("/conversations/{id}/pending-checkpoint")
    public ApiResponse<Map<String, Object>> getPendingCheckpoint(
            Authentication auth, @PathVariable String id) {
        chatService.getOwnedConversation(auth.getName(), id);
        Map<String, Object> cp = chatService.getPendingCheckpoint(id);
        return ApiResponse.ok(cp);
    }

    /** 跳过当前会话的待处理 HITL checkpoint（「换个话题」按钮用；刷新后不再恢复旧卡片） */
    @PostMapping("/conversations/{id}/checkpoint/skip")
    public ApiResponse<Void> skipPendingCheckpoint(
            Authentication auth, @PathVariable String id) {
        chatService.skipPendingCheckpoints(auth.getName(), id);
        return ApiResponse.ok("已跳过当前确认", null);
    }

    /** 删除会话（级联删消息/资产） */
    @DeleteMapping("/conversations/{id}")
    public ApiResponse<Void> deleteConversation(Authentication auth, @PathVariable String id) {
        chatService.deleteConversation(auth.getName(), id);
        return ApiResponse.ok("删除成功", null);
    }

    /** 清空会话聊天记录（删消息；会话与生成资产保留，其他会话不受影响） */
    @DeleteMapping("/conversations/{id}/messages")
    public ApiResponse<Void> clearMessages(Authentication auth, @PathVariable String id) {
        chatService.clearMessages(auth.getName(), id);
        return ApiResponse.ok("已清空", null);
    }

    /** 消息列表 */
    @GetMapping("/conversations/{id}/messages")
    public ApiResponse<List<AgentMessageVO>> listMessages(
            Authentication auth, @PathVariable String id) {
        chatService.getOwnedConversation(auth.getName(), id);
        return ApiResponse.ok(chatService.listMessages(id).stream().map(AgentConversationController::toVO).toList());
    }

    /** 发送消息 */
    @PostMapping("/conversations/{id}/messages")
    public ApiResponse<AgentMessageVO> sendMessage(
            Authentication auth, @PathVariable String id,
            @Valid @RequestBody AgentSendMessageRequest request) {
        return ApiResponse.ok(toVO(chatService.sendMessage(auth.getName(), id, request.content())));
    }

    /** 资产列表（分页） */
    @GetMapping("/conversations/{id}/assets")
    public ApiResponse<Map<String, Object>> listAssets(
            Authentication auth, @PathVariable String id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        chatService.getOwnedConversation(auth.getName(), id);
        return ApiResponse.ok(chatService.listAssets(id, page, size));
    }

    /** 上传图片（参考图）：存 uploads/images/ → 返回 URL → 落库 agent_assets(type=reference) */
    @PostMapping("/upload")
    public ApiResponse<Map<String, String>> uploadImage(
              Authentication auth,
              @RequestParam("file") MultipartFile file,
              @RequestParam(required = false) String conversationId,
              @RequestParam(required = false) String purpose,
              @RequestParam(required = false) String kind) {
        // 参数校验（文件类型）：Controller 职责
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BusinessException(40001, "仅支持上传图片文件");
        }
        String url = chatService.uploadReferenceImage(auth.getName(), conversationId, file, purpose, kind);
        return ApiResponse.ok(Map.of("url", url));
    }

    /** 提示词优化：草稿 → 优化后的专业提示词（LLM 自判类型；≥6 字符；不落库、不关联会话） */
    @PostMapping("/prompt/optimize")
    public ApiResponse<Map<String, String>> optimizePrompt(
            Authentication auth, @RequestBody PromptOptimizeRequest request) {
        // 双端校验：与前端按钮禁用条件一致（<6 字符无法优化），防绕过前端
        if (request.content() == null || request.content().trim().length() < 6) {
            throw new BusinessException(40001, "内容至少 6 个字符才能优化");
        }
        return ApiResponse.ok(Map.of("optimized", optimizeService.optimize(request.content().trim())));
    }

    /** 满意完成：清空会话图片上下文（生成结果确认卡片「满意完成」按钮触发） */
    @PostMapping("/conversations/{id}/confirm-done")
    public ApiResponse<Boolean> confirmDone(Authentication auth, @PathVariable String id) {
        // 归属校验（40401）+ 清空图片上下文；失败抛 50202，前端保留卡片可重试
        return ApiResponse.ok(chatService.confirmImageDone(auth.getName(), id));
    }

    /** 打断当前会话的生成（「停止生成」按钮触发）：置位会话级 interrupt 标志 → 编排协作式取消 */
    @PostMapping("/conversations/{id}/interrupt")
    public ApiResponse<Boolean> interrupt(Authentication auth, @PathVariable String id) {
        return ApiResponse.ok(chatService.interrupt(auth.getName(), id));
    }

    /** 流式发送消息（SSE） */
    @PostMapping(value = "/conversations/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(Authentication auth, @PathVariable String id,
                                    @Valid @RequestBody AgentSendMessageRequest request) {
        // 同步快速校验归属（失败抛 401/404 而非 SSE）
        chatService.getOwnedConversation(auth.getName(), id);
        // 同步校验消息内容非空：空内容直接抛 40001（与 blocking sendMessage 语义一致），
        // 避免 service 在 emitter 未初始化前 sendEvent 抛 IllegalStateException 被吞掉、客户端收到空 200
        if (request.content() == null || request.content().isBlank()) {
            throw new BusinessException(40001, "消息内容不能为空");
        }
        SseEmitter emitter = createSseEmitter();
        java.util.List<String> resolvedPicUrls = new java.util.ArrayList<>(picUrls(request));
        if (request.assetGroupIds() != null && !request.assetGroupIds().isEmpty()) {
            for (String groupId : request.assetGroupIds()) {
                if (groupId == null || groupId.isBlank()) continue;
                AssetGroupSnapshot snapshot = assetClient.getSnapshot(
                        groupId, auth.getName(), java.util.UUID.randomUUID().toString());
                if (snapshot == null || snapshot.items() == null) continue;
                snapshot.items().stream()
                        .filter(item -> "IMAGE".equals(item.type().name()))
                        .map(AssetContracts.SnapshotItem::contentUrl)
                        .filter(url -> url != null && !url.isBlank())
                        .forEach(resolvedPicUrls::add);
            }
        }
        String routingHint = request.assetGroupIds() != null && !request.assetGroupIds().isEmpty()
                ? "intent-video" : request.routingHint();
        chatService.streamMessage(auth.getName(), id, request.content(), resolvedPicUrls,
                request.assetIds() == null ? java.util.List.of() : request.assetIds(), emitter,
                routingHint, Boolean.TRUE.equals(request.cancelActiveTasks()));
        return emitter;
    }

    /** HITL 表单提交并续流（SSE） */
    @PostMapping(value = "/conversations/{id}/form/submit", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter submitForm(Authentication auth, @PathVariable String id,
                                 @RequestBody AgentFormSubmitRequest request) {
        chatService.getOwnedConversation(auth.getName(), id);
        SseEmitter emitter = createSseEmitter();
        chatService.submitFormAndResume(auth.getName(), id,
            request.formToken(), request.taskId(), request.action(), request.content(),
            request.params() == null ? java.util.Map.of() : request.params(),
            request.assetIds() == null ? java.util.List.of() : request.assetIds(),
            request.routingHint(), emitter);
        return emitter;
    }

    /** 图生视频方案确认后生成（video_plan 事件「开始生成视频」触发，SSE） */
    @PostMapping(value = "/conversations/{id}/video/plan/generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateVideoFromPlan(Authentication auth, @PathVariable String id,
                                            @RequestBody AgentVideoPlanGenerateRequest request) {
        // 同步快速校验归属（失败抛 401/404 而非 SSE）
        chatService.getOwnedConversation(auth.getName(), id);
        SseEmitter emitter = createSseEmitter();
        chatService.generateVideoFromPlan(auth.getName(), id, request.planToken(), emitter);
        return emitter;
    }

    /** 视频异步任务状态（前端轮询：task_accepted 后 5s 间隔 GET，取 status/url/error） */
    @GetMapping("/tasks/{taskId}")
    public ApiResponse<Map<String, Object>> getVideoTaskStatus(Authentication auth, @PathVariable String taskId) {
        return ApiResponse.ok(chatService.getVideoTaskStatus(auth.getName(), taskId));
    }

    /** 用户级生成记录列表（跨项目/会话，created_at 倒序，支持类型/日期/状态筛选） */
    @GetMapping("/generation-records")
    public ApiResponse<Map<String, Object>> listGenerationRecords(
            Authentication auth,
            @RequestParam(required = false) String genType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(generationRecordService.listRecords(
                auth.getName(), genType, status, parseDate(from), parseDate(to), page, size));
    }

    /** 生成记录详情 */
    @GetMapping("/generation-records/{id}")
    public ApiResponse<AgentAsset> getGenerationRecord(Authentication auth, @PathVariable String id) {
        return ApiResponse.ok(generationRecordService.getRecord(auth.getName(), id));
    }

    /** 当前项目产出素材：按对话分组，当前对话优先，供分镜审查素材选择器使用。 */
    @GetMapping("/projects/{projectId}/output-materials")
    public ApiResponse<List<ProjectOutputMaterialGroup>> projectOutputMaterials(
            Authentication auth,
            @PathVariable String projectId,
            @RequestParam(required = false) String currentConversationId) {
        return ApiResponse.ok(projectOutputMaterialService.list(auth.getName(), projectId, currentConversationId));
    }

    /** 重新拉取（仅视频且 taskId 非空）：重新轮询上游并更新记录 */
    @PostMapping("/generation-records/{id}/refetch")
    public ApiResponse<AgentAsset> refetchGenerationRecord(Authentication auth, @PathVariable String id) {
        return ApiResponse.ok(generationRecordService.refetch(auth.getName(), id));
    }

    /** 重试（仅 failed 记录）：复用原参数重新生成，产出新记录 */
    @PostMapping("/generation-records/{id}/retry")
    public ApiResponse<Map<String, String>> retryGenerationRecord(Authentication auth, @PathVariable String id) {
        return ApiResponse.ok(generationRecordService.retry(auth.getName(), id));
    }

    /** 重命名 / 归档会话 */
    @PatchMapping("/conversations/{id}")
    public ApiResponse<AgentConversationVO> updateConversation(Authentication auth, @PathVariable String id,
                                                             @RequestBody AgentConversationUpdateRequest request) {
        return ApiResponse.ok(toVO(chatService.updateConversation(
                auth.getName(), id, request.title(), request.status())));
    }

    /** 删除资产（仅限归属本人会话；未归属资产拒绝） */
    @DeleteMapping("/assets/{id}")
    public ApiResponse<Void> deleteAsset(Authentication auth, @PathVariable String id) {
        chatService.deleteAsset(auth.getName(), id);
        return ApiResponse.ok("删除成功", null);
    }

    /** 会话实体 → VO 映射 */
    private static AgentConversationVO toVO(AgentConversation c) {
        return new AgentConversationVO(c.getId(), c.getUserId(), c.getProjectId(), c.getTitle(),
                c.getStatus(), c.getCreatedAt(), c.getUpdatedAt());
    }

    /** 消息实体 → VO 映射 */
    private static AgentMessageVO toVO(AgentMessage m) {
        return new AgentMessageVO(m.getId(), m.getConversationId(), m.getRole(), m.getContent(),
                m.getMetadata() == null ? java.util.Map.of() : m.getMetadata(),
                m.getCreatedAt());
    }

    /** picUrls 兼容包装：picUrls 优先；为空回退 [picUrl]（旧客户端只传 picUrl） */
    private static List<String> picUrls(AgentSendMessageRequest r) {
        if (r.picUrls() != null && !r.picUrls().isEmpty()) return r.picUrls();
        if (r.picUrl() != null && !r.picUrl().isBlank()) return List.of(r.picUrl());
        return List.of();
    }

    /** 统一 SSE 生命周期，默认 1200 秒以覆盖长剧本分批生成。 */
    private SseEmitter createSseEmitter() {
        long timeoutMillis = Math.max(1, agentConfig.getSseTimeoutSeconds()) * 1000L;
        return new SseEmitter(timeoutMillis);
    }

    /** yyyy-MM-dd → LocalDate；非法/空返回 null（不筛选） */
    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            return null;
        }
    }
}
