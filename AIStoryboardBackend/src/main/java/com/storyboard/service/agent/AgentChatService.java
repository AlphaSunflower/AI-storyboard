package com.storyboard.service.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyboard.entity.AgentConversation;
import com.storyboard.entity.AgentMessage;
import com.storyboard.exception.BusinessException;
import com.storyboard.mapper.AgentConversationMapper;
import com.storyboard.mapper.AgentMessageMapper;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.service.ai.AiConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
 *
 * 事务边界说明（I1）：
 * - user 消息用独立事务（REQUIRES_NEW）保存并立即提交；
 * - Dify 调用失败时 user 消息保留（独立事务已提交），assistant 消息不落库；
 * - Dify 成功后，回填 difyConversationId + 保存 assistant 消息在同一事务内完成。
 */
@Service
public class AgentChatService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final ProjectMapper projectMapper;
    private final AiConfigProperties config;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public AgentChatService(AgentConversationMapper conversationMapper,
                            AgentMessageMapper messageMapper,
                            ProjectMapper projectMapper,
                            AiConfigProperties config,
                            PlatformTransactionManager transactionManager) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.projectMapper = projectMapper;
        this.config = config;
        // user 消息保存使用独立事务（REQUIRES_NEW）：即使外层存在事务，也单独提交，
        // 保证 Dify 调用失败时 user 消息不被回滚。
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 创建会话：校验项目归属，返回会话 */
    public AgentConversation createConversation(String userId, String projectId, String title) {
        var project = projectMapper.selectById(projectId);
        if (project == null) throw new BusinessException(40401, "项目不存在");
        if (!userId.equals(project.getUserId())) throw new BusinessException(40301, "无权为该项目创建对话");

        AgentConversation conversation = new AgentConversation();
        conversation.setUserId(userId);
        conversation.setProjectId(projectId);
        conversation.setTitle(title != null && !title.isBlank() ? title : "新对话");
        conversation.setStatus("active");
        conversationMapper.insert(conversation);
        return conversation;
    }

    /**
     * 校验会话归属，返回会话。
     * 会话不存在与无权访问统一返回 40401 + 同一文案（M4），防止 IDOR 枚举。
     */
    public AgentConversation getOwnedConversation(String userId, String conversationId) {
        AgentConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !userId.equals(conversation.getUserId())) {
            throw new BusinessException(40401, "会话不存在或无权访问");
        }
        return conversation;
    }

    /** 会话消息列表（created_at 正序） */
    public List<AgentMessage> listMessages(String conversationId) {
        return messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
            .eq(AgentMessage::getConversationId, conversationId)
            .orderByAsc(AgentMessage::getCreatedAt));
    }

    /**
     * 发送消息：落库 user 消息（独立事务）→ 调 Dify chat-messages → 回填 + 落库 assistant 消息。
     *
     * 事务语义（I1）：
     * - user 消息在独立事务（REQUIRES_NEW）中立即提交；
     * - Dify 失败时 user 消息保留（独立事务已提交），assistant 消息不落库，抛业务异常；
     * - Dify 成功后，"回填 difyConversationId + 保存 assistant 消息"在同一事务内完成。
     *
     * @return assistant 消息
     */
    public AgentMessage sendMessage(String userId, String conversationId, String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(40001, "消息内容不能为空");
        }
        AgentConversation conversation = getOwnedConversation(userId, conversationId);

        // 1. 保存 user 消息 —— 独立事务（REQUIRES_NEW），Dify 失败时保留
        AgentMessage userMessage = new AgentMessage();
        userMessage.setConversationId(conversationId);
        userMessage.setRole("user");
        userMessage.setContent(content);
        transactionTemplate.executeWithoutResult(tx -> messageMapper.insert(userMessage));

        // 2. 调 Dify chat-messages
        Map<String, Object> result;
        try {
            result = callDifyChat(conversation, content, userId);
        } catch (Exception e) {
            // M8：失败路径也刷新 conversation 的 updatedAt（updateById 触发 MyBatis fill），
            // 反映最近一次对话尝试；刷新失败只记日志，不掩盖原始异常。
            try {
                transactionTemplate.executeWithoutResult(tx -> conversationMapper.updateById(conversation));
            } catch (Exception ex) {
                log.warn("刷新会话 updatedAt 失败: conversationId={}", conversationId, ex);
            }
            throw e;
        }

        // 3. 成功：事务性完成"回填 difyConversationId + 保存 assistant 消息 + 刷新 updatedAt"
        return transactionTemplate.execute(tx -> {
            String difyConversationId = (String) result.get("conversationId");
            if (difyConversationId != null && !difyConversationId.isBlank()
                    && !difyConversationId.equals(conversation.getDifyConversationId())) {
                conversation.setDifyConversationId(difyConversationId);
            }
            // M8：成功路径也刷新 updatedAt（updateById 触发 MyBatis fill）
            conversationMapper.updateById(conversation);

            AgentMessage assistantMessage = new AgentMessage();
            assistantMessage.setConversationId(conversationId);
            assistantMessage.setRole("assistant");
            assistantMessage.setContent((String) result.getOrDefault("answer", ""));
            assistantMessage.setDifyMessageId((String) result.get("messageId"));
            messageMapper.insert(assistantMessage);
            return assistantMessage;
        });
    }

    /** 调用 Dify /v1/chat-messages（blocking 模式） */
    private Map<String, Object> callDifyChat(AgentConversation conversation,
                                              String query, String userId) {
        try {
            Map<String, Object> body = new HashMap<>();
            // M11：project_name 用 projectMapper 查真实项目名（查不到保持空串）
            String projectName = "";
            var project = projectMapper.selectById(conversation.getProjectId());
            if (project != null && project.getName() != null) {
                projectName = project.getName();
            }
            body.put("inputs", Map.of(
                "project_id", conversation.getProjectId(),
                "project_name", projectName
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
                // M5：上游错误完整信息只进日志，抛给客户端的文案脱敏
                log.error("Dify chat-messages 返回非 200: status={}, body={}",
                        resp.statusCode(), resp.body());
                throw new BusinessException(50202, "Dify 服务异常，请稍后重试");
            }

            JsonNode root = objectMapper.readTree(resp.body());
            Map<String, Object> result = new HashMap<>();
            result.put("answer", root.path("answer").asText(""));
            result.put("conversationId", root.path("conversation_id").asText(""));
            result.put("messageId", root.path("message_id").asText(""));
            return result;
        } catch (BusinessException e) {
            // 已脱敏的业务异常直接透传（文案不携带上游细节）
            throw e;
        } catch (Exception e) {
            // M5：完整异常（含根因堆栈）只进日志
            log.error("Dify chat-messages 调用失败: conversationId={}, error={}",
                    conversation.getId(), e.getMessage(), e);
            throw new BusinessException(50202, "Dify 服务异常，请稍后重试");
        }
    }
}
