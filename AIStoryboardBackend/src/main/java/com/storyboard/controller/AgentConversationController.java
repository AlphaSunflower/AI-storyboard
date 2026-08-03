package com.storyboard.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.storyboard.dto.request.AgentCreateConversationRequest;
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
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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

    /** 会话生成资产列表 */
    @GetMapping("/conversations/{id}/assets")
    public ApiResponse<List<AgentAsset>> listAssets(Authentication auth, @PathVariable String id) {
        chatService.getOwnedConversation(auth.getName(), id);
        List<AgentAsset> assets = assetMapper.selectList(
            new LambdaQueryWrapper<AgentAsset>()
                .eq(AgentAsset::getConversationId, id)
                .orderByDesc(AgentAsset::getCreatedAt));
        return ApiResponse.ok(assets);
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
}
