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

    public AgentConversationController(AgentChatService chatService,
                                       AgentConversationMapper conversationMapper,
                                       AgentAssetMapper assetMapper) {
        this.chatService = chatService;
        this.conversationMapper = conversationMapper;
        this.assetMapper = assetMapper;
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
}
