package com.moon.moonagent.controller;

import com.moon.moonagent.dto.request.AgentConversationUpdateRequest;
import com.moon.moonagent.dto.request.AgentEditMessageRequest;
import com.moon.moonagent.dto.request.AgentAssetGroupBindingRequest;
import com.moon.moonagent.dto.request.AgentApprovalModeUpdateRequest;
import com.moon.moonagent.dto.request.AgentCreateConversationRequest;
import com.moon.moonagent.dto.request.AgentFormSubmitRequest;
import com.moon.moonagent.dto.request.AgentSendMessageRequest;
import com.moon.moonagent.dto.request.AgentSteerRequest;
import com.moon.moonagent.dto.request.AgentVideoPlanGenerateRequest;
import com.moon.moonagent.dto.request.PromptOptimizeRequest;
import com.moon.moonagent.dto.response.AgentConversationVO;
import com.moon.moonagent.dto.response.AgentMessageVO;
import com.moon.moonagent.dto.response.AgentSteerResponse;
import com.moon.moonagent.dto.response.MessagePageVO;
import com.moon.moonagent.dto.response.MessageSearchHitVO;
import com.moon.moonagent.dto.response.ProjectOutputMaterialGroup;
import com.moon.moonagent.dto.response.ConversationAssetGroupVO;
import com.storyboard.common.ApiResponse;
import com.storyboard.asset.client.AssetClient;
import com.storyboard.asset.client.AssetContracts;
import com.storyboard.asset.client.AssetContracts.AssetGroupSnapshot;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;
import com.storyboard.common.BusinessException;
import com.moon.moonagent.ai.agent.AgentChatService;
import com.moon.moonagent.ai.agent.ClearContextResult;
import com.moon.moonagent.ai.agent.AgentGenerationPreferences;
import com.moon.moonagent.ai.agent.AgentReferenceUpload;
import com.moon.moonagent.ai.agent.CanvasSessionContext;
import com.moon.moonagent.ai.agent.GenerationRecordService;
import com.moon.moonagent.ai.agent.PromptOptimizeService;
import com.moon.moonagent.ai.agent.turn.AgentTurnEventEnvelope;
import com.moon.moonagent.ai.AgentAiConfigProperties;
import com.moon.moonagent.security.FileUrlSigner;
import com.moon.moonagent.service.ProjectOutputMaterialService;
import com.moon.moonagent.service.ConversationAssetGroupService;
import com.moon.moonagent.service.TurnMediaCanonicalizer;
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
    private final FileUrlSigner fileUrlSigner;
    private final ProjectOutputMaterialService projectOutputMaterialService;
    private final AgentAiConfigProperties agentConfig;
    private final AssetClient assetClient;
    private final ConversationAssetGroupService conversationAssetGroupService;
    private final com.moon.moonagent.ai.agent.agentscope.AgentCanvasSessionService canvasSessionService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.moon.moonagent.ai.agent.orchestration.SubagentRunQueryService subagentRunQueryService;
    private final TurnMediaCanonicalizer turnMediaCanonicalizer;

    /** 创建会话 */
    @PostMapping("/conversations")
    public ApiResponse<AgentConversationVO> createConversation(
            Authentication auth, @Valid @RequestBody AgentCreateConversationRequest request) {
        return ApiResponse.ok(toVO(chatService.createConversation(
                auth.getName(), request.projectId(), request.title(),
                request.orchestrationMode(), request.approvalMode())));
    }

    /** 当前用户的项目会话列表（updated_at 倒序） */
    @GetMapping("/conversations")
    public ApiResponse<List<AgentConversationVO>> listConversations(
            Authentication auth, @RequestParam String projectId) {
        return ApiResponse.ok(chatService.listConversations(auth.getName(), projectId).stream()
                .map(AgentConversationController::toVO).toList());
    }

    @GetMapping("/conversations/{id}/asset-groups")
    public ApiResponse<List<ConversationAssetGroupVO>> listAssetGroups(
            Authentication auth,
            @PathVariable String id
    ) {
        return ApiResponse.ok(conversationAssetGroupService.list(auth.getName(), id));
    }

    @PostMapping("/conversations/{id}/asset-groups")
    public ApiResponse<ConversationAssetGroupVO> attachAssetGroup(
            Authentication auth,
            @PathVariable String id,
            @Valid @RequestBody AgentAssetGroupBindingRequest request
    ) {
        return ApiResponse.ok(conversationAssetGroupService.attach(
                auth.getName(), id, request.groupId()));
    }

    @DeleteMapping("/conversations/{id}/asset-groups/{groupId}")
    public ApiResponse<Void> removeAssetGroup(
            Authentication auth,
            @PathVariable String id,
            @PathVariable String groupId
    ) {
        conversationAssetGroupService.remove(auth.getName(), id, groupId);
        return ApiResponse.ok("已移除资产组", null);
    }

    /** 会话详情（含消息列表） */
    @GetMapping("/conversations/{id}")
    public ApiResponse<Map<String, Object>> getConversation(
            Authentication auth, @PathVariable String id) {
        AgentConversation conversation = chatService.getOwnedConversation(auth.getName(), id);
        List<AgentMessageVO> messages = chatService.listMessages(id).stream()
                .map(this::toVO).toList();
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

    /** 离开画布编辑模式：清除会话级画布锁，后续消息恢复全局意图识别。 */
    @PostMapping("/conversations/{id}/canvas-mode/exit")
    public ApiResponse<Void> exitCanvasMode(Authentication auth, @PathVariable String id) {
        chatService.exitCanvasMode(auth.getName(), id);
        return ApiResponse.ok("已离开画布编辑模式", null);
    }

    /** 删除会话（级联删消息/资产） */
    @DeleteMapping("/conversations/{id}")
    public ApiResponse<Void> deleteConversation(Authentication auth, @PathVariable String id) {
        chatService.deleteConversation(auth.getName(), id);
        return ApiResponse.ok("删除成功", null);
    }

    /** 清空会话上下文（真删除：消息 + 轮次事件 + checkpoint + 画布会话…，会话与产出素材保留） */
    @DeleteMapping("/conversations/{id}/messages")
    public ApiResponse<Void> clearMessages(Authentication auth, @PathVariable String id) {
        chatService.clearMessages(auth.getName(), id);
        return ApiResponse.ok("已清空", null);
    }

    /**
     * 「/清空上下文」：把会话真删成「刚建出来的新对话」。
     *
     * <p>与 {@code DELETE /conversations/{id}/messages} 的区别：这里做的是硬删除 +
     * 彻底重置（消息、轮次与直播事件、待确认 checkpoint、画布会话与运行、文档索引、
     * 异步任务、时间线、评审、工作流、资产组绑定全部 DELETE），并重置标题与会话初始化状态。
     * 产出素材仍保留。
     */
    @PostMapping("/conversations/{id}/context/clear")
    public ApiResponse<ClearContextResult> clearContext(
            Authentication auth, @PathVariable String id) {
        return ApiResponse.ok(chatService.clearContext(auth.getName(), id));
    }

    /** 消息列表 */
    @GetMapping("/conversations/{id}/messages")
    public ApiResponse<List<AgentMessageVO>> listMessages(
            Authentication auth, @PathVariable String id) {
        chatService.getOwnedConversation(auth.getName(), id);
        return ApiResponse.ok(chatService.listMessages(id).stream().map(this::toVO).toList());
    }

    /** 消息稳定游标分页；旧接口保留 500 条兼容期。 */
    @GetMapping("/conversations/{id}/messages/page")
    public ApiResponse<MessagePageVO> listMessagesPage(
            Authentication auth, @PathVariable String id,
            @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(chatService.listMessagesPage(auth.getName(), id, before, limit));
    }

    /** 当前用户消息全文搜索，替代前端逐会话全量建索引。 */
    @GetMapping("/messages/search")
    public ApiResponse<List<MessageSearchHitVO>> searchMessages(
            Authentication auth,
            @RequestParam String q,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(chatService.searchMessages(auth.getName(), q, limit));
    }

    /** 发送消息 */
    @PostMapping("/conversations/{id}/messages")
    public ApiResponse<AgentMessageVO> sendMessage(
            Authentication auth, @PathVariable String id,
            @Valid @RequestBody AgentSendMessageRequest request) {
        throw new BusinessException(41001,
                "旧同步消息接口已停用，请使用 /conversations/{id}/turns/stream");
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

    /** 上传对话附件：保存到 MinIO → 落库 agent_assets(type=reference)，返回可持久化的文件元数据。 */
    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> uploadImage(
              Authentication auth,
              @RequestParam("file") MultipartFile file,
              @RequestParam(required = false) String conversationId,
              @RequestParam(required = false) String purpose,
              @RequestParam(required = false) String kind) {
        AgentReferenceUpload uploaded = chatService.uploadReferenceImage(
                auth.getName(), conversationId, file, purpose, kind);
        return ApiResponse.ok(Map.of(
                "assetId", uploaded.assetId(),
                "url", uploaded.url(),
                "name", uploaded.name(),
                "mimeType", uploaded.mimeType(),
                "sizeBytes", uploaded.sizeBytes()
        ));
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
        throw new BusinessException(41001,
                "旧消息流已停用，请使用 /conversations/{id}/turns/stream");
    }

    /** AgentScope turn stream using sequenced native event envelopes. */
    @PostMapping(value = "/conversations/{id}/turns/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTurn(Authentication auth, @PathVariable String id,
                                 @Valid @RequestBody AgentSendMessageRequest request) {
        chatService.getOwnedConversation(auth.getName(), id);
        if (request.content() == null || request.content().isBlank()) {
            throw new BusinessException(40001, "消息内容不能为空");
        }
        // 媒体地址归一化 + 可信性校验（拒绝不可信地址，剥掉浏览器 origin）
        request = turnMediaCanonicalizer.canonicalize(request);
        ResolvedTurnMedia media = resolveTurnMedia(auth, id, request);
        SseEmitter emitter = createSseEmitter();
        if (request.attachments() != null && !request.attachments().isEmpty()) {
            chatService.streamAgentScopeTurn(
                    auth.getName(), id, request.content(), media.picUrls(), media.assetIds(),
                    request.routingHint(), request.editMessageId(), request.canvasContext(), media.videoUrls(),
                    media.audioUrls(), request.clientRequestId(), request.attachments(),
                    request.references(),
                    generationPreferences(request), emitter);
        } else {
            chatService.streamAgentScopeTurn(
                    auth.getName(), id, request.content(), media.picUrls(), media.assetIds(),
                    request.routingHint(), request.editMessageId(), request.canvasContext(), media.videoUrls(),
                    media.audioUrls(), request.clientRequestId(), List.of(),
                    request.references(),
                    generationPreferences(request), emitter);
        }
        return emitter;
    }

    /**
     * 客户端生成偏好 → 领域对象。自动（缺省）且不忽略方案时等价于不带该字段，
     * 行为与本轮改造前完全一致。
     */
    private static AgentGenerationPreferences generationPreferences(AgentSendMessageRequest request) {
        com.moon.moonagent.dto.request.AgentGenerationPreferencesRequest preferences =
                request.generationPreferences();
        if (preferences == null) return AgentGenerationPreferences.none();
        return AgentGenerationPreferences.of(
                preferences.auto() == null || preferences.auto(),
                Boolean.TRUE.equals(preferences.ignoreGenerationPlan()),
                preferences.image(),
                preferences.video());
    }

    /** Resume an AgentScope HITL checkpoint using the sequenced event protocol. */
    @PostMapping(value = "/conversations/{id}/turns/{turnId}/resume/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resumeTurn(Authentication auth, @PathVariable String id,
                                 @PathVariable String turnId,
                                 @RequestBody AgentFormSubmitRequest request) {
        chatService.getOwnedConversation(auth.getName(), id);
        SseEmitter emitter = createSseEmitter();
        chatService.resumeAgentScopeTurn(
                auth.getName(), id, turnId, request.formToken(), request.action(), request.content(),
                request.params() == null ? Map.of() : request.params(),
                request.assetIds() == null ? List.of() : request.assetIds(),
                turnMediaCanonicalizer.canonicalizeCanvasContext(request.canvasContext()), emitter);
        return emitter;
    }

    /** Resume a canvas turn after its iframe is ready. */
    @PostMapping(value = "/conversations/{id}/canvas-sessions/{sessionId}/ready",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter readyCanvasSession(Authentication auth,
                                         @PathVariable String id,
                                         @PathVariable String sessionId,
                                         @RequestBody(required = false) Map<String, Object> request) {
        chatService.getOwnedConversation(auth.getName(), id);
        SseEmitter emitter = createSseEmitter();
        String selectedCanvasId = request == null || request.get("canvasId") == null
                ? "" : String.valueOf(request.get("canvasId"));
        boolean createNew = request != null && Boolean.TRUE.equals(request.get("createNew"));
        chatService.resumeCanvasSession(
                auth.getName(), id, sessionId, selectedCanvasId, createNew, emitter);
        return emitter;
    }

    /** Cancel a pending canvas session. */
    @PostMapping("/conversations/{id}/canvas-sessions/{sessionId}/cancel")
    public ApiResponse<Map<String, Object>> cancelCanvasSession(Authentication auth,
                                                                 @PathVariable String id,
                                                                 @PathVariable String sessionId) {
        chatService.getOwnedConversation(auth.getName(), id);
        return ApiResponse.ok(canvasSessionService
                .cancel(auth.getName(), id, sessionId).toMap());
    }

    /** Return the current pending canvas session for refresh recovery. */
    @GetMapping("/conversations/{id}/canvas-sessions/pending")
    public ApiResponse<Map<String, Object>> pendingCanvasSession(Authentication auth,
                                                                  @PathVariable String id) {
        chatService.getOwnedConversation(auth.getName(), id);
        var pending = canvasSessionService.pending(auth.getName(), id);
        return ApiResponse.ok(pending == null ? Map.of() : pending.toMap());
    }

    /** Interrupt endpoint aligned with the turn protocol. */
    @PostMapping("/conversations/{id}/turns/{turnId}/interrupt")
    public ApiResponse<Boolean> interruptTurn(Authentication auth, @PathVariable String id,
                                              @PathVariable String turnId) {
        return ApiResponse.ok(chatService.interrupt(auth.getName(), id));
    }

    /**
     * 回合中插入一条用户消息（steer）。回合已结束时返回 {@code not_running}，
     * 前端据此改走普通发送（自动开新一轮）。
     */
    @PostMapping("/conversations/{id}/turns/{turnId}/steer")
    public ApiResponse<AgentSteerResponse> steerTurn(Authentication auth,
                                                     @PathVariable String id,
                                                     @PathVariable String turnId,
                                                     @RequestBody AgentSteerRequest request) {
        return ApiResponse.ok(chatService.steerTurn(
                auth.getName(), id, turnId, request.content(), request.clientMessageId()));
    }

    /** Runtime health/session status for the AgentScope transport. */
    @GetMapping("/conversations/{id}/runtime")
    public ApiResponse<Map<String, Object>> runtimeStatus(Authentication auth,
                                                          @PathVariable String id) {
        return ApiResponse.ok(chatService.getRuntimeStatus(auth.getName(), id));
    }

    /** 手动压缩会话上下文（/chat 斜杠命令「压缩上下文」）。 */
    @PostMapping("/conversations/{id}/compact")
    public ApiResponse<Map<String, Object>> compactContext(Authentication auth,
                                                           @PathVariable String id) {
        return ApiResponse.ok(chatService.compactContext(auth.getName(), id));
    }

    /** 基于当前会话创建临时侧边分支。 */
    @PostMapping("/conversations/{id}/side-chats")
    public ApiResponse<AgentConversationVO> createSideChat(
            Authentication auth, @PathVariable String id) {
        return ApiResponse.ok(toVO(chatService.createSideChat(auth.getName(), id)));
    }

    /** 关闭并删除临时侧边分支。 */
    @DeleteMapping("/side-chats/{id}")
    public ApiResponse<Void> closeSideChat(
            Authentication auth, @PathVariable String id) {
        chatService.closeSideChat(auth.getName(), id);
        return ApiResponse.ok("侧边已关闭", null);
    }

    /** Replay native AgentScope turn events after a sequence for reconnect. */
    @GetMapping("/conversations/{id}/turns/{turnId}/events")
    public ApiResponse<List<AgentTurnEventEnvelope>> turnEvents(
            Authentication auth, @PathVariable String id, @PathVariable String turnId,
            @RequestParam(defaultValue = "0") int afterSeq) {
        return ApiResponse.ok(chatService.getTurnEvents(
                auth.getName(), id, turnId, afterSeq));
    }

    /** 子智能体任务图的节点级审计查询。 */
    /** 生成失败节点重跑提示词（不直接执行，避免绕过父级 HITL）。 */
    @PostMapping("/subagent-runs/{runId}/retry")
    public ApiResponse<Map<String, Object>> retrySubagentRun(
            Authentication auth, @PathVariable String runId,
            @RequestParam String conversationId) {
        chatService.getOwnedConversation(auth.getName(), conversationId);
        if (subagentRunQueryService == null) {
            return ApiResponse.ok(Map.of("prompt", "", "conversationId", conversationId));
        }
        return ApiResponse.ok(subagentRunQueryService.retryPrompt(runId, conversationId));
    }

    @GetMapping("/subagent-runs")
    public ApiResponse<List<com.moon.moonagent.dto.response.SubagentRunView>> subagentRuns(
            Authentication auth,
            @RequestParam String conversationId,
            @RequestParam(required = false) String graphId,
            @RequestParam(defaultValue = "50") int limit) {
        chatService.getOwnedConversation(auth.getName(), conversationId);
        if (subagentRunQueryService == null) {
            return ApiResponse.ok(List.of());
        }
        return ApiResponse.ok(subagentRunQueryService.list(conversationId, graphId, limit));
    }

    /**
     * 续传某个 turn 的事件流（刷新 / 新开页面接上正在跑的 turn）。
     *
     * <p>先补 {@code afterSeq} 之后的持久化事件，再持续推送新事件直到终态；turn 已终态时
     * 补完历史即结束。该端点不重复执行 turn，订阅者断开也不会中断 turn。
     */
    @GetMapping(value = "/conversations/{id}/turns/{turnId}/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTurnEvents(Authentication auth, @PathVariable String id,
                                       @PathVariable String turnId,
                                       @RequestParam(defaultValue = "0") long afterSeq) {
        chatService.getOwnedConversation(auth.getName(), id);
        SseEmitter emitter = createSseEmitter();
        chatService.openTurnEventStream(auth.getName(), id, turnId, afterSeq, emitter);
        return emitter;
    }

    /** 编辑最后一轮用户消息并覆盖重发（SSE）。 */
    @PostMapping(value = "/conversations/{id}/messages/{messageId}/edit-and-resend",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter editAndResend(Authentication auth, @PathVariable String id,
                                    @PathVariable String messageId,
                                    @Valid @RequestBody AgentEditMessageRequest request) {
        throw new BusinessException(41001,
                "旧编辑重发接口已停用，请使用 /turns/stream 并传入 editMessageId");
    }

    /** 打断当前生成并把下一条消息作为新轮发送（SSE）。 */
    @PostMapping(value = "/conversations/{id}/messages/interrupt-and-send",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter interruptAndSend(Authentication auth, @PathVariable String id,
                                       @Valid @RequestBody AgentSendMessageRequest request) {
        throw new BusinessException(41001,
                "旧打断并发送接口已停用，请先调用 /turns/{turnId}/interrupt，再调用 /turns/stream");
    }

    /** HITL 表单提交并续流（SSE） */
    @PostMapping(value = "/conversations/{id}/form/submit", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter submitForm(Authentication auth, @PathVariable String id,
                                 @RequestBody AgentFormSubmitRequest request) {
        throw new BusinessException(41001,
                "旧 HITL 提交接口已停用，请使用 /turns/{turnId}/resume/stream");
    }

    /** 图生视频方案确认后生成（video_plan 事件「开始生成视频」触发，SSE） */
    @PostMapping(value = "/conversations/{id}/video/plan/generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateVideoFromPlan(Authentication auth, @PathVariable String id,
                                            @RequestBody AgentVideoPlanGenerateRequest request) {
        throw new BusinessException(41001,
                "旧视频方案接口已停用，请使用 AgentScope HITL resume");
    }

    /** 视频异步任务状态（前端轮询：task_accepted 后 5s 间隔 GET，取 status/url/error） */
    @GetMapping("/legacy-tasks/{taskId}")
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
        return ApiResponse.ok(signAsset(generationRecordService.getRecord(auth.getName(), id)));
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
        return ApiResponse.ok(signAsset(generationRecordService.refetch(auth.getName(), id)));
    }

    private AgentAsset signAsset(AgentAsset asset) {
        asset.setUrl(fileUrlSigner.signIfLocal(asset.getUrl()));
        asset.setResultUrls(fileUrlSigner.signJsonArray(asset.getResultUrls()));
        return asset;
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

    /** 更新会话级 AgentScope 审批模式；运行中的 turn 不允许切换。 */
    @PatchMapping("/conversations/{id}/approval-mode")
    public ApiResponse<AgentConversationVO> updateApprovalMode(
            Authentication auth,
            @PathVariable String id,
            @Valid @RequestBody AgentApprovalModeUpdateRequest request) {
        return ApiResponse.ok(toVO(chatService.updateApprovalMode(
                auth.getName(), id, request.approvalMode())));
    }

    /**
     * 删除资产（仅限归属本人会话；未归属资产拒绝）。
     *
     * <p>不带 {@code resultIndex} 时删除整条；带上时只删该条结果里的第 N 张，
     * 索引口径与前端会话资产网格一致（见 AgentChatService#deleteAssetResult）。
     */
    @DeleteMapping("/assets/{id}")
    public ApiResponse<Void> deleteAsset(Authentication auth, @PathVariable String id,
                                         @RequestParam(required = false) Integer resultIndex) {
        if (resultIndex == null) {
            chatService.deleteAsset(auth.getName(), id);
        } else {
            chatService.deleteAssetResult(auth.getName(), id, resultIndex);
        }
        return ApiResponse.ok("删除成功", null);
    }

    private ResolvedTurnMedia resolveTurnMedia(Authentication auth, String conversationId,
                                               AgentSendMessageRequest request) {
        List<String> resolvedPicUrls = new java.util.ArrayList<>(picUrls(request));
        List<String> resolvedVideos = new java.util.ArrayList<>();
        List<String> resolvedAudios = new java.util.ArrayList<>();
        if (request.referenceVideos() != null) resolvedVideos.addAll(request.referenceVideos());
        if (request.referenceAudios() != null) resolvedAudios.addAll(request.referenceAudios());
        for (ConversationAssetGroupVO group
                : conversationAssetGroupService.list(auth.getName(), conversationId)) {
            for (ConversationAssetGroupVO.SnapshotMediaVO media : group.media()) {
                if (media.contentUrl() == null || media.contentUrl().isBlank()) continue;
                switch (media.type()) {
                    case "IMAGE" -> resolvedPicUrls.add(media.contentUrl());
                    case "VIDEO" -> resolvedVideos.add(media.contentUrl());
                    case "AUDIO" -> resolvedAudios.add(media.contentUrl());
                    default -> { }
                }
            }
        }
        if (request.assetGroupSnapshotIds() != null) {
            for (String snapshotId : request.assetGroupSnapshotIds()) {
                if (snapshotId == null || snapshotId.isBlank()) continue;
                AssetGroupSnapshot snapshot = assetClient.getSnapshot(
                        snapshotId, auth.getName(), java.util.UUID.randomUUID().toString());
                if (snapshot == null || snapshot.items() == null) continue;
                snapshot.items().stream()
                        .filter(item -> "IMAGE".equals(item.type().name()))
                        .map(AssetContracts.SnapshotItem::contentUrl)
                        .filter(url -> url != null && !url.isBlank())
                        .forEach(resolvedPicUrls::add);
                snapshot.items().stream()
                        .filter(item -> "VIDEO".equals(item.type().name()))
                        .map(AssetContracts.SnapshotItem::contentUrl)
                        .filter(url -> url != null && !url.isBlank())
                        .forEach(resolvedVideos::add);
                snapshot.items().stream()
                        .filter(item -> "AUDIO".equals(item.type().name()))
                        .map(AssetContracts.SnapshotItem::contentUrl)
                        .filter(url -> url != null && !url.isBlank())
                        .forEach(resolvedAudios::add);
            }
        }
        return new ResolvedTurnMedia(
                resolvedPicUrls.stream().distinct().toList(),
                request.assetIds() == null ? List.of() : request.assetIds(),
                resolvedVideos.stream().distinct().toList(),
                resolvedAudios.stream().distinct().toList());
    }

    private record ResolvedTurnMedia(List<String> picUrls, List<String> assetIds,
                                     List<String> videoUrls, List<String> audioUrls) {
    }

    /** 会话实体 → VO 映射 */
    private static AgentConversationVO toVO(AgentConversation c) {
        CanvasSessionContext canvasContext = null;
        if (c.getCanvasContext() != null && !c.getCanvasContext().isBlank()) {
            try {
                canvasContext = CanvasSessionContext.fromJson(c.getCanvasContext());
            } catch (Exception e) {
                log.warn("解析会话画布上下文失败: conversationId={}", c.getId());
            }
        }
        return new AgentConversationVO(c.getId(), c.getUserId(), c.getProjectId(), c.getTitle(),
                c.getStatus(), canvasContext,
                c.getOrchestrationMode() == null ? "AGENTSCOPE" : c.getOrchestrationMode(),
                c.getEngineVersion() == null ? "agentscope-java-2.0.4" : c.getEngineVersion(),
                c.getDebugComparisonEnabled() == null || c.getDebugComparisonEnabled(),
                c.getApprovalMode() == null ? "PLAN_APPROVAL" : c.getApprovalMode(),
                c.getCreatedAt(), c.getUpdatedAt());
    }

    /** 消息实体 → VO 映射 */
    private AgentMessageVO toVO(AgentMessage m) {
        String content = m.getContent();
        String signedContent = fileUrlSigner.signEmbeddedUrls(content);
        Map<String, Object> metadata = m.getMetadata() == null ? java.util.Map.of() : m.getMetadata();
        Map<String, Object> signedMetadata = fileUrlSigner.signMetadata(metadata);
        if (signedMetadata == null || (signedMetadata.isEmpty() && !metadata.isEmpty())) {
            signedMetadata = metadata;
        }
        return new AgentMessageVO(m.getId(), m.getConversationId(), m.getRole(),
                signedContent == null ? content : signedContent,
                signedMetadata,
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
