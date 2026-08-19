package com.moon.moonagent.controller;

import com.moon.moonagent.dto.request.AgentConversationUpdateRequest;
import com.moon.moonagent.dto.request.AgentCreateConversationRequest;
import com.moon.moonagent.dto.request.AgentFormSubmitRequest;
import com.moon.moonagent.dto.request.AgentSendMessageRequest;
import com.moon.moonagent.dto.request.AgentVideoPlanGenerateRequest;
import com.moon.moonagent.dto.request.PromptOptimizeRequest;
import com.moon.moonagent.dto.response.AgentConversationVO;
import com.moon.moonagent.dto.response.AgentMessageVO;
import com.storyboard.common.ApiResponse;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;
import com.storyboard.common.BusinessException;
import com.moon.moonagent.ai.agent.AgentChatService;
import com.moon.moonagent.ai.agent.PromptOptimizeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
            @RequestParam(required = false) String conversationId) {
        // 参数校验（文件类型）：Controller 职责
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BusinessException(40001, "仅支持上传图片文件");
        }
        String url = chatService.uploadReferenceImage(auth.getName(), conversationId, file);
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
        SseEmitter emitter = new SseEmitter(600_000L);
        chatService.streamMessage(auth.getName(), id, request.content(), request.picUrl(), emitter);
        return emitter;
    }

    /** HITL 表单提交并续流（SSE） */
    @PostMapping(value = "/conversations/{id}/form/submit", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter submitForm(Authentication auth, @PathVariable String id,
                                 @RequestBody AgentFormSubmitRequest request) {
        chatService.getOwnedConversation(auth.getName(), id);
        SseEmitter emitter = new SseEmitter(600_000L);
        chatService.submitFormAndResume(auth.getName(), id,
            request.formToken(), request.taskId(), request.action(), request.content(),
            request.params() == null ? java.util.Map.of() : request.params(),
            request.assetIds() == null ? java.util.List.of() : request.assetIds(), emitter);
        return emitter;
    }

    /** 图生视频方案确认后生成（video_plan 事件「开始生成视频」触发，SSE） */
    @PostMapping(value = "/conversations/{id}/video/plan/generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateVideoFromPlan(Authentication auth, @PathVariable String id,
                                            @RequestBody AgentVideoPlanGenerateRequest request) {
        // 同步快速校验归属（失败抛 401/404 而非 SSE）
        chatService.getOwnedConversation(auth.getName(), id);
        SseEmitter emitter = new SseEmitter(600_000L);
        chatService.generateVideoFromPlan(auth.getName(), id, request.planToken(), emitter);
        return emitter;
    }

    /** 视频异步任务状态（前端轮询：task_accepted 后 5s 间隔 GET，取 status/url/error） */
    @GetMapping("/tasks/{taskId}")
    public ApiResponse<Map<String, Object>> getVideoTaskStatus(Authentication auth, @PathVariable String taskId) {
        return ApiResponse.ok(chatService.getVideoTaskStatus(auth.getName(), taskId));
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
                m.getCreatedAt());
    }
}
