package com.storyboard.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.storyboard.dto.request.AgentConversationUpdateRequest;
import com.storyboard.dto.request.AgentCreateConversationRequest;
import com.storyboard.dto.request.AgentFormSubmitRequest;
import com.storyboard.dto.request.AgentSendMessageRequest;
import com.storyboard.dto.response.ApiResponse;
import com.storyboard.entity.AgentAsset;
import com.storyboard.entity.AgentConversation;
import com.storyboard.entity.AgentMessage;
import com.storyboard.mapper.AgentAssetMapper;
import com.storyboard.mapper.AgentConversationMapper;
import com.storyboard.exception.BusinessException;
import com.storyboard.service.FileStorageService;
import com.storyboard.service.agent.AgentChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Agent 对话模块 —— JWT 鉴权（/api/agent/** 不在 SecurityConfig 白名单）。
 * 会话 / 消息 / 资产 / 图片上传。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentConversationController {

    private static final Logger log = LoggerFactory.getLogger(AgentConversationController.class);

    private final AgentChatService chatService;
    private final AgentConversationMapper conversationMapper;
    private final AgentAssetMapper assetMapper;
    private final FileStorageService fileStorageService;

    public AgentConversationController(AgentChatService chatService,
                                       AgentConversationMapper conversationMapper,
                                       AgentAssetMapper assetMapper,
                                       FileStorageService fileStorageService) {
        this.chatService = chatService;
        this.conversationMapper = conversationMapper;
        this.assetMapper = assetMapper;
        this.fileStorageService = fileStorageService;
    }

    /** 创建会话 */
    @PostMapping("/conversations")
    public ApiResponse<AgentConversation> createConversation(
            Authentication auth, @RequestBody AgentCreateConversationRequest request) {
        return ApiResponse.ok(chatService.createConversation(auth.getName(), request.projectId(), request.title()));
    }

    /** 当前用户的项目会话列表（updated_at 倒序） */
    @GetMapping("/conversations")
    public ApiResponse<List<AgentConversation>> listConversations(
            Authentication auth, @RequestParam String projectId) {
        List<AgentConversation> list = conversationMapper.selectList(
            new LambdaQueryWrapper<AgentConversation>()
                .eq(AgentConversation::getUserId, auth.getName())
                .eq(AgentConversation::getProjectId, projectId)
                .orderByDesc(AgentConversation::getUpdatedAt));
        return ApiResponse.ok(list);
    }

    /** 会话详情（含消息列表） */
    @GetMapping("/conversations/{id}")
    public ApiResponse<Map<String, Object>> getConversation(
            Authentication auth, @PathVariable String id) {
        AgentConversation conversation = chatService.getOwnedConversation(auth.getName(), id);
        List<AgentMessage> messages = chatService.listMessages(id);
        return ApiResponse.ok(Map.of("conversation", conversation, "messages", messages));
    }

    /** 删除会话（级联删消息） */
    @DeleteMapping("/conversations/{id}")
    public ApiResponse<Void> deleteConversation(Authentication auth, @PathVariable String id) {
        AgentConversation conversation = chatService.getOwnedConversation(auth.getName(), id);
        conversationMapper.deleteById(conversation.getId());
        return ApiResponse.ok("删除成功", null);
    }

    /** 清空会话聊天记录（删消息 + 重置 Dify 上下文；会话与生成资产保留，其他会话不受影响） */
    @DeleteMapping("/conversations/{id}/messages")
    public ApiResponse<Void> clearMessages(Authentication auth, @PathVariable String id) {
        chatService.clearMessages(auth.getName(), id);
        return ApiResponse.ok("已清空", null);
    }

    /** 消息列表 */
    @GetMapping("/conversations/{id}/messages")
    public ApiResponse<List<AgentMessage>> listMessages(
            Authentication auth, @PathVariable String id) {
        chatService.getOwnedConversation(auth.getName(), id);
        return ApiResponse.ok(chatService.listMessages(id));
    }

    /** 发送消息（代理 Dify） */
    @PostMapping("/conversations/{id}/messages")
    public ApiResponse<AgentMessage> sendMessage(
            Authentication auth, @PathVariable String id,
            @RequestBody AgentSendMessageRequest request) {
        return ApiResponse.ok(chatService.sendMessage(auth.getName(), id, request.content()));
    }

    /** 资产列表（分页，手写 LIMIT/OFFSET——项目未装 MyBatis-Plus 分页插件） */
    // M2：count+list 置于同一只读事务，避免并发写导致的分页总数与记录不一致
    @Transactional(readOnly = true)
    @GetMapping("/conversations/{id}/assets")
    public ApiResponse<Map<String, Object>> listAssets(
            Authentication auth, @PathVariable String id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        chatService.getOwnedConversation(auth.getName(), id);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(50, Math.max(1, size));
        long total = assetMapper.selectCount(
            new LambdaQueryWrapper<AgentAsset>().eq(AgentAsset::getConversationId, id));
        List<AgentAsset> records = assetMapper.selectList(
            new LambdaQueryWrapper<AgentAsset>()
                .eq(AgentAsset::getConversationId, id)
                .orderByDesc(AgentAsset::getCreatedAt)
                // 先转 long 再乘，避免 (safePage - 1) * safeSize 在 int 域溢出（OFFSET 超出 21 亿行时）
                .last("LIMIT " + safeSize + " OFFSET " + ((long) (safePage - 1) * safeSize)));
        return ApiResponse.ok(Map.of("records", records, "total", total, "page", safePage, "size", safeSize));
    }

    /** 上传图片（参考图）：存 uploads/images/ → 返回 URL → 落库 agent_assets(type=reference) */
    @PostMapping("/upload")
    public ApiResponse<Map<String, String>> uploadImage(
            Authentication auth,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String conversationId) {
        // 校验会话归属（传了 conversationId 时）
        if (conversationId != null && !conversationId.isBlank()) {
            chatService.getOwnedConversation(auth.getName(), conversationId);
        }
        // 校验文件类型（I2：模块内校验错误改抛 BusinessException）
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BusinessException(40001, "仅支持上传图片文件");
        }
        String url = fileStorageService.saveUploadedImage(file);

        // 落库 agent_assets（type=reference）
        AgentAsset asset = new AgentAsset();
        asset.setConversationId(conversationId != null && !conversationId.isBlank() ? conversationId : null);
        asset.setType("reference");
        asset.setUrl(url);
        asset.setStatus("completed");
        assetMapper.insert(asset);
        log.info("Agent 参考图已落库: assetId={}, url={}", asset.getId(), url);

        return ApiResponse.ok(Map.of(
            "url", url,
            "assetId", asset.getId()
        ));
    }

    /** 流式发送消息（SSE） */
    @PostMapping(value = "/conversations/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(Authentication auth, @PathVariable String id,
                                    @RequestBody AgentSendMessageRequest request) {
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
            request.formToken(), request.taskId(), request.action(), emitter);
        return emitter;
    }

    /** 重命名 / 归档会话 */
    @PatchMapping("/conversations/{id}")
    public ApiResponse<AgentConversation> updateConversation(Authentication auth, @PathVariable String id,
                                                             @RequestBody AgentConversationUpdateRequest request) {
        AgentConversation conversation = chatService.getOwnedConversation(auth.getName(), id);
        if (request.title() != null && !request.title().isBlank()) {
            conversation.setTitle(request.title().trim());
        }
        if (request.status() != null && !request.status().isBlank()) {
            if (!"active".equals(request.status()) && !"archived".equals(request.status())) {
                throw new BusinessException(40001, "会话状态非法");
            }
            conversation.setStatus(request.status());
        }
        // PATCH 后手动刷新 updatedAt：MyBatis-Plus strictUpdateFill 仅在字段为 null 时填充，
        // 实体加载后 updatedAt 非空会写回旧值，导致列表按 updated_at 倒序时重命名/归档不置顶
        conversation.setUpdatedAt(OffsetDateTime.now());
        conversationMapper.updateById(conversation);
        return ApiResponse.ok(conversation);
    }

    /** 删除资产（仅限归属本人会话；未归属资产拒绝） */
    @DeleteMapping("/assets/{id}")
    public ApiResponse<Void> deleteAsset(Authentication auth, @PathVariable String id) {
        AgentAsset asset = assetMapper.selectById(id);
        if (asset == null || asset.getConversationId() == null || asset.getConversationId().isBlank()) {
            throw new BusinessException(40401, "资产不存在或无权访问");
        }
        chatService.getOwnedConversation(auth.getName(), asset.getConversationId());
        assetMapper.deleteById(id);
        return ApiResponse.ok("删除成功", null);
    }
}
