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
import com.storyboard.mapper.SceneMapper;
import com.storyboard.service.ai.AiConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Agent 对话服务 —— 代理 Dify /v1/chat-messages（blocking + streaming）。
 * 负责：会话校验、消息落库、Dify 调用、conversation_id 回填、SSE 流式转发、HITL 表单提交续流。
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
    private final SceneMapper sceneMapper;
    private final AiConfigProperties config;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public AgentChatService(AgentConversationMapper conversationMapper,
                            AgentMessageMapper messageMapper,
                            ProjectMapper projectMapper,
                            SceneMapper sceneMapper,
                            AiConfigProperties config,
                            PlatformTransactionManager transactionManager) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.projectMapper = projectMapper;
        this.sceneMapper = sceneMapper;
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
            // Moon 工作流 start 节点变量：currentProjectId（项目 ID）+ PicUrl（参考图 URL）
            body.put("inputs", Map.of(
                "currentProjectId", conversation.getProjectId(),
                "PicUrl", ""
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

    /**
     * 流式发送消息：user 消息独立事务提交 → 代理 Dify streaming → 事件裁剪转发到 SseEmitter。
     * 收到 human_input_required → 转发 human_input 事件 → 结束流（Dify 侧 pause 自动关闭）。
     * message_end → 落库 assistant 消息 + 回填 dify_conversation_id + 附带 sceneCount。
     */
    public void streamMessage(String userId, String conversationId, String content, String picUrl, SseEmitter emitter) {
        if (content == null || content.isBlank()) {
            sendEvent(emitter, "error", Map.of("code", "40001", "message", "消息内容不能为空"));
            emitter.complete();
            return;
        }
        AgentConversation conversation = getOwnedConversation(userId, conversationId);

        // 1. user 消息独立事务立即提交
        AgentMessage userMessage = new AgentMessage();
        userMessage.setConversationId(conversationId);
        userMessage.setRole("user");
        userMessage.setContent(content);
        transactionTemplate.executeWithoutResult(tx -> messageMapper.insert(userMessage));

        // 2. 异步代理 Dify（SseEmitter 需异步写，否则阻塞 Controller 返回）
        CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> body = buildChatBody(conversation, content, picUrl);
                body.put("response_mode", "streaming");
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getDifyBaseUrl() + "/v1/chat-messages"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.getDifyApiKey())
                    .timeout(Duration.ofSeconds(600))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
                HttpResponse<java.io.InputStream> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() != 200) {
                    log.error("Dify chat-messages streaming 非 200: status={}", resp.statusCode());
                    sendEvent(emitter, "error", Map.of("code", "50202", "message", "Dify 服务异常，请稍后重试"));
                    emitter.complete();
                    return;
                }
                forwardDifySse(resp, emitter, conversation, userId, new StringBuilder());
            } catch (Exception e) {
                log.error("Dify streaming 调用失败: conversationId={}, error={}", conversationId, e.getMessage(), e);
                sendEvent(emitter, "error", Map.of("code", "50202", "message", "Dify 服务异常，请稍后重试"));
                emitter.complete();
            }
        });
    }

    /** 构建 Dify chat-messages 请求体（streaming/blocking 共用） */
    private Map<String, Object> buildChatBody(AgentConversation conversation, String query, String picUrl) {
        Map<String, Object> body = new HashMap<>();
        body.put("inputs", Map.of(
            "currentProjectId", conversation.getProjectId(),
            "PicUrl", picUrl == null ? "" : picUrl
        ));
        body.put("query", query);
        body.put("user", conversation.getUserId());
        if (conversation.getDifyConversationId() != null && !conversation.getDifyConversationId().isBlank()) {
            body.put("conversation_id", conversation.getDifyConversationId());
        }
        return body;
    }

    /** SseEmitter 事件发送（捕获 IOException 忽略——前端已断开） */
    private void sendEvent(SseEmitter emitter, String eventName, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception e) {
            log.debug("SseEmitter 发送失败（前端可能已断开）: event={}", eventName);
        }
    }

    /**
     * 逐行读取 Dify SSE 流，裁剪转发给前端。
     * 事件映射：
     *   message                → 累积 answer，转发 {type:message, content:增量}
     *   node_started/finished  → 转发 {type:workflow, title, status}（丢弃 inputs/outputs）
     *   human_input_required   → 转发 {type:human_input, formToken, taskId, formContent, actions, expirationTime}，结束流
     *   message_end            → 落库 assistant + 回填 + 转发 {type:message_end, messageId, sceneCount}，结束流
     *   error                  → 转发 {type:error, code, message}，结束流
     *   ping 等其余            → 忽略
     */
    private void forwardDifySse(HttpResponse<java.io.InputStream> resp, SseEmitter emitter,
                                AgentConversation conversation, String userId, StringBuilder answer) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                JsonNode node;
                try {
                    node = objectMapper.readTree(line.substring(5).trim());
                } catch (Exception e) {
                    continue; // 忽略无法解析的行
                }
                String event = node.path("event").asText("");
                switch (event) {
                    case "message" -> {
                        String delta = node.path("answer").asText("");
                        answer.append(delta);
                        sendEvent(emitter, "message", Map.of("content", delta));
                    }
                    case "node_started", "node_finished" -> sendEvent(emitter, "workflow", Map.of(
                        "title", node.path("data").path("title").asText(""),
                        "status", "node_started".equals(event) ? "node_started" : "node_finished"));
                    case "human_input_required" -> {
                        JsonNode data = node.path("data");
                        List<Map<String, String>> actions = new ArrayList<>();
                        for (JsonNode a : data.path("actions")) {
                            actions.add(Map.of("id", a.path("id").asText(""), "title", a.path("title").asText("")));
                        }
                        sendEvent(emitter, "human_input", Map.of(
                            "formToken", data.path("form_token").asText(""),
                            "taskId", node.path("task_id").asText(""),
                            "formContent", data.path("form_content").asText(""),
                            "actions", actions,
                            "expirationTime", data.path("expiration_time").asLong(0)));
                        // HITL 暂停：落库已累积的方案文本，结束当前流
                        persistAssistant(conversation, answer.toString(), null);
                        emitter.complete();
                        return;
                    }
                    case "message_end" -> {
                        String messageId = node.path("message_id").asText("");
                        String difyConvId = node.path("conversation_id").asText("");
                        persistAssistant(conversation, answer.toString(), messageId);
                        if (difyConvId != null && !difyConvId.isBlank()
                                && !difyConvId.equals(conversation.getDifyConversationId())) {
                            conversation.setDifyConversationId(difyConvId);
                            conversationMapper.updateById(conversation);
                        }
                        long sceneCount = sceneMapper.selectCount(
                            new LambdaQueryWrapper<com.storyboard.entity.Scene>()
                                .eq(com.storyboard.entity.Scene::getProjectId, conversation.getProjectId()));
                        sendEvent(emitter, "message_end", Map.of("messageId", messageId, "sceneCount", sceneCount));
                        emitter.complete();
                        return;
                    }
                    case "error" -> {
                        sendEvent(emitter, "error", Map.of(
                            "code", node.path("code").asText("50202"),
                            "message", "Dify 服务异常，请稍后重试"));
                        emitter.complete();
                        return;
                    }
                    default -> { /* ping 等忽略 */ }
                }
            }
            // 流正常 EOF（无 message_end 的兜底）
            if (answer.length() > 0) persistAssistant(conversation, answer.toString(), null);
            emitter.complete();
        } catch (Exception e) {
            log.error("Dify SSE 读取失败: conversationId={}", conversation.getId(), e);
            sendEvent(emitter, "error", Map.of("code", "50202", "message", "Dify 服务异常，请稍后重试"));
            emitter.complete();
        }
    }

    /** 落库 assistant 消息（事务内：保存消息 + 刷新会话 updatedAt） */
    private void persistAssistant(AgentConversation conversation, String content, String difyMessageId) {
        if (content == null || content.isBlank()) return;
        transactionTemplate.executeWithoutResult(tx -> {
            AgentMessage assistantMessage = new AgentMessage();
            assistantMessage.setConversationId(conversation.getId());
            assistantMessage.setRole("assistant");
            assistantMessage.setContent(content);
            assistantMessage.setDifyMessageId(difyMessageId);
            messageMapper.insert(assistantMessage);
            conversationMapper.updateById(conversation); // 触发 updatedAt fill
        });
    }

    /**
     * HITL 表单提交并续流：
     * 1. POST {base}/v1/form/human_input/{formToken}（body {action}）
     * 2. 成功 → GET {base}/v1/workflow/{taskId}/events?user={userId} 续传 SSE（复用 forwardDifySse）
     */
    public void submitFormAndResume(String userId, String conversationId, String formToken, String taskId, String action, SseEmitter emitter) {
        AgentConversation conversation = getOwnedConversation(userId, conversationId);
        CompletableFuture.runAsync(() -> {
            try {
                HttpRequest submitReq = HttpRequest.newBuilder()
                    .uri(URI.create(config.getDifyBaseUrl() + "/v1/form/human_input/" + formToken))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.getDifyApiKey())
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(Map.of("action", action))))
                    .build();
                HttpResponse<String> submitResp = httpClient.send(submitReq, HttpResponse.BodyHandlers.ofString());
                if (submitResp.statusCode() != 200) {
                    log.error("Dify 表单提交失败: status={}, body={}", submitResp.statusCode(), submitResp.body());
                    sendEvent(emitter, "error", Map.of("code", "50202", "message", "Dify 服务异常，请稍后重试"));
                    emitter.complete();
                    return;
                }
                // 续流：workflow events 端点 user 参数必填（已核实 Dify 源码）
                HttpRequest eventsReq = HttpRequest.newBuilder()
                    .uri(URI.create(config.getDifyBaseUrl() + "/v1/workflow/" + taskId
                        + "/events?user=" + userId))
                    .header("Authorization", "Bearer " + config.getDifyApiKey())
                    .timeout(Duration.ofSeconds(600))
                    .GET()
                    .build();
                HttpResponse<java.io.InputStream> eventsResp = httpClient.send(eventsReq, HttpResponse.BodyHandlers.ofInputStream());
                if (eventsResp.statusCode() != 200) {
                    sendEvent(emitter, "error", Map.of("code", "50202", "message", "Dify 服务异常，请稍后重试"));
                    emitter.complete();
                    return;
                }
                forwardDifySse(eventsResp, emitter, conversation, userId, new StringBuilder());
            } catch (Exception e) {
                log.error("Dify HITL 提交/续流失败: conversationId={}, error={}", conversationId, e.getMessage(), e);
                sendEvent(emitter, "error", Map.of("code", "50202", "message", "Dify 服务异常，请稍后重试"));
                emitter.complete();
            }
        });
    }
}
