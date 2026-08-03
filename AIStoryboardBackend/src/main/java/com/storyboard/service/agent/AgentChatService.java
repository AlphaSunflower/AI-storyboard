package com.storyboard.service.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.entity.AgentConversation;
import com.storyboard.entity.AgentMessage;
import com.storyboard.mapper.AgentConversationMapper;
import com.storyboard.mapper.AgentMessageMapper;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.service.ai.AiConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 对话服务 —— 代理 Dify /v1/chat-messages（blocking 模式）。
 * 负责：会话校验、消息落库、Dify 调用、conversation_id 回填。
 */
@Service
public class AgentChatService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final ProjectMapper projectMapper;
    private final AiConfigProperties config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public AgentChatService(AgentConversationMapper conversationMapper,
                            AgentMessageMapper messageMapper,
                            ProjectMapper projectMapper,
                            AiConfigProperties config) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.projectMapper = projectMapper;
        this.config = config;
    }

    /** 创建会话：校验项目归属，返回会话 */
    public AgentConversation createConversation(String userId, String projectId, String title) {
        var project = projectMapper.selectById(projectId);
        if (project == null) throw new RuntimeException("项目不存在: " + projectId);
        if (!userId.equals(project.getUserId())) throw new RuntimeException("无权为该项目创建对话");

        AgentConversation conversation = new AgentConversation();
        conversation.setUserId(userId);
        conversation.setProjectId(projectId);
        conversation.setTitle(title != null && !title.isBlank() ? title : "新对话");
        conversation.setStatus("active");
        conversationMapper.insert(conversation);
        return conversation;
    }

    /** 校验会话归属，返回会话 */
    public AgentConversation getOwnedConversation(String userId, String conversationId) {
        AgentConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) throw new RuntimeException("会话不存在: " + conversationId);
        if (!userId.equals(conversation.getUserId())) throw new RuntimeException("无权访问该会话");
        return conversation;
    }

    /** 会话消息列表（created_at 正序） */
    public List<AgentMessage> listMessages(String conversationId) {
        return messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
            .eq(AgentMessage::getConversationId, conversationId)
            .orderByAsc(AgentMessage::getCreatedAt));
    }

    /**
     * 发送消息：落库 user 消息 → 调 Dify chat-messages → 落库 assistant 消息。
     * 返回 assistant 消息；失败时 user 消息保留，抛异常。
     */
    @Transactional
    public AgentMessage sendMessage(String userId, String conversationId, String content) {
        if (content == null || content.isBlank()) {
            throw new RuntimeException("消息内容不能为空");
        }
        AgentConversation conversation = getOwnedConversation(userId, conversationId);

        // 1. 保存 user 消息
        AgentMessage userMessage = new AgentMessage();
        userMessage.setConversationId(conversationId);
        userMessage.setRole("user");
        userMessage.setContent(content);
        messageMapper.insert(userMessage);

        // 2. 调 Dify chat-messages
        Map<String, Object> result = callDifyChat(conversation, content, userId);

        // 3. 回填 Dify conversation_id（首次对话后才有）
        String difyConversationId = (String) result.get("conversationId");
        if (difyConversationId != null && !difyConversationId.isBlank()
                && !difyConversationId.equals(conversation.getDifyConversationId())) {
            conversation.setDifyConversationId(difyConversationId);
            conversationMapper.updateById(conversation);
        }

        // 4. 保存 assistant 消息
        AgentMessage assistantMessage = new AgentMessage();
        assistantMessage.setConversationId(conversationId);
        assistantMessage.setRole("assistant");
        assistantMessage.setContent((String) result.getOrDefault("answer", ""));
        assistantMessage.setDifyMessageId((String) result.get("messageId"));
        messageMapper.insert(assistantMessage);
        return assistantMessage;
    }

    /** 调用 Dify /v1/chat-messages（blocking 模式） */
    private Map<String, Object> callDifyChat(AgentConversation conversation,
                                              String query, String userId) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("inputs", Map.of(
                "project_id", conversation.getProjectId(),
                "project_name", ""
            ));
            body.put("query", query);
            body.put("response_mode", "blocking");
            body.put("user", userId);
            if (conversation.getDifyConversationId() != null
                    && !conversation.getDifyConversationId().isBlank()) {
                body.put("conversation_id", conversation.getDifyConversationId());
            }

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getDifyBaseUrl() + "/v1/chat-messages"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getDifyApiKey())
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("Dify API 返回 " + resp.statusCode() + ": " + resp.body());
            }

            JsonNode root = objectMapper.readTree(resp.body());
            Map<String, Object> result = new HashMap<>();
            result.put("answer", root.path("answer").asText(""));
            result.put("conversationId", root.path("conversation_id").asText(""));
            result.put("messageId", root.path("message_id").asText(""));
            return result;
        } catch (Exception e) {
            log.error("Dify chat-messages 调用失败: conversationId={}, error={}",
                    conversation.getId(), e.getMessage());
            throw new RuntimeException("Dify 对话失败: " + e.getMessage(), e);
        }
    }
}
