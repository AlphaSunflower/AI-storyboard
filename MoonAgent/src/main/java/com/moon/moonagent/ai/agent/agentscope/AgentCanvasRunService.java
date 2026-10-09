package com.moon.moonagent.ai.agent.agentscope;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.entity.AgentCanvasRunEntity;
import com.moon.moonagent.entity.AgentCanvasRunOperationEntity;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.entity.MorrowCanvas;
import com.moon.moonagent.mapper.AgentCanvasRunMapper;
import com.moon.moonagent.mapper.AgentCanvasRunOperationMapper;
import com.moon.moonagent.mapper.AgentMessageMapper;
import com.moon.moonagent.service.MorrowCanvasService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Durable state for a canvas graph built by the current Agent turn.
 *
 * <p>A Canvas Run is intentionally not a pause/checkpoint. It is a normal
 * asynchronous artifact: the turn keeps running, every graph command updates
 * the run, and the browser iframe is only a viewer.
 */
@Service
@RequiredArgsConstructor
public class AgentCanvasRunService {

    public static final String RUNNING = "running";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";
    public static final String CANCELLED = "cancelled";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentCanvasRunMapper runMapper;
    private final AgentCanvasRunOperationMapper operationMapper;
    private final MorrowCanvasService canvasService;
    private final AgentMessageMapper messageMapper;
    @Autowired(required = false)
    private transient PlatformTransactionManager transactionManager;
    private volatile TransactionTemplate transactionTemplate;

    public CanvasRunView create(AgentConversation conversation,
                                String turnId,
                                String toolCallId,
                                String title,
                                String intent,
                                List<Map<String, Object>> plan) {
        return createInternal(conversation, turnId, toolCallId, null, title, intent, plan);
    }

    public CanvasRunView createExisting(AgentConversation conversation,
                                        String turnId,
                                        String toolCallId,
                                        String canvasId,
                                        String intent,
                                        String title,
                                        List<Map<String, Object>> plan) {
        return createInternal(conversation, turnId, toolCallId, canvasId, title, intent, plan);
    }

    private CanvasRunView createInternal(AgentConversation conversation,
                                         String turnId,
                                         String toolCallId,
                                         String existingCanvasId,
                                         String title,
                                         String intent,
                                         List<Map<String, Object>> plan) {
        if (conversation == null || conversation.getId() == null || conversation.getId().isBlank()) {
            throw new IllegalArgumentException("缺少会话，无法创建画布任务");
        }
        if (conversation.getProjectId() == null || conversation.getProjectId().isBlank()) {
            throw new IllegalStateException("当前会话尚未绑定项目，无法创建画布任务");
        }
        CanvasRunView existing = find(turnId, toolCallId);
        if (existing != null) return existing;

        String normalizedTitle = normalizeTitle(title);
        MorrowCanvas canvas = existingCanvasId == null || existingCanvasId.isBlank()
                ? canvasService.create(conversation.getUserId(), conversation.getProjectId(), normalizedTitle)
                : canvasService.get(conversation.getUserId(), existingCanvasId);

        return inTransaction(() ->
                persistCreated(conversation, turnId, toolCallId, canvas, intent, normalizedTitle, plan));
    }

    private CanvasRunView persistCreated(AgentConversation conversation,
                                         String turnId,
                                         String toolCallId,
                                         MorrowCanvas canvas,
                                         String intent,
                                         String normalizedTitle,
                                         List<Map<String, Object>> plan) {
        AgentCanvasRunEntity entity = new AgentCanvasRunEntity();
        entity.setConversationId(conversation.getId());
        entity.setUserId(conversation.getUserId());
        entity.setProjectId(conversation.getProjectId());
        entity.setTurnId(turnId);
        entity.setToolCallId(toolCallId);
        entity.setCanvasId(canvas.getId());
        entity.setTitle(canvas.getTitle() == null || canvas.getTitle().isBlank()
                ? normalizedTitle : canvas.getTitle());
        entity.setStatus(RUNNING);
        entity.setProgress(0);
        entity.setNodeCount(0);
        entity.setEdgeCount(0);
        entity.setRevision(0);
        entity.setPlanJson(write(plan == null ? List.of() : plan));
        runMapper.insert(entity);

        AgentMessage message = new AgentMessage();
        message.setConversationId(conversation.getId());
        message.setRole("assistant");
        message.setContent("");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", "canvas-run");
        metadata.put("canvasRunId", entity.getId());
        metadata.put("canvasId", entity.getCanvasId());
        metadata.put("turnId", turnId);
        metadata.put("title", entity.getTitle());
        metadata.put("intent", intent == null ? "" : intent);
        metadata.put("status", entity.getStatus());
        metadata.put("progress", entity.getProgress());
        metadata.put("nodeCount", 0);
        metadata.put("edgeCount", 0);
        metadata.put("revision", 0);
        message.setMetadata(metadata);
        messageMapper.insert(message);
        entity.setMessageId(message.getId());
        runMapper.updateById(entity);

        return view(entity);
    }

    private TransactionTemplate transactionTemplate() {
        if (transactionTemplate == null) {
            synchronized (this) {
                if (transactionTemplate == null) {
                    TransactionTemplate template = new TransactionTemplate(transactionManager);
                    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    transactionTemplate = template;
                }
            }
        }
        return transactionTemplate;
    }

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        if (transactionManager == null) return action.get();
        return transactionTemplate().execute(tx -> action.get());
    }

    public CanvasRunView find(String turnId, String toolCallId) {
        if (turnId == null || turnId.isBlank() || toolCallId == null || toolCallId.isBlank()) {
            return null;
        }
        AgentCanvasRunEntity entity = runMapper.selectOne(
                new LambdaQueryWrapper<AgentCanvasRunEntity>()
                        .eq(AgentCanvasRunEntity::getTurnId, turnId)
                        .eq(AgentCanvasRunEntity::getToolCallId, toolCallId)
                        .last("LIMIT 1"));
        return entity == null ? null : view(entity);
    }

    public CanvasRunView get(String runId) {
        if (runId == null || runId.isBlank()) return null;
        AgentCanvasRunEntity entity = runMapper.selectById(runId);
        return entity == null ? null : view(entity);
    }

    public CanvasRunView activeByTurn(String turnId) {
        if (turnId == null || turnId.isBlank()) return null;
        AgentCanvasRunEntity entity = runMapper.selectOne(
                new LambdaQueryWrapper<AgentCanvasRunEntity>()
                        .eq(AgentCanvasRunEntity::getTurnId, turnId)
                        .orderByDesc(AgentCanvasRunEntity::getCreatedAt)
                        .last("LIMIT 1"));
        return entity == null ? null : view(entity);
    }

    @Transactional
    public CanvasRunView updateFromGraphResult(String runId, Map<String, Object> result) {
        AgentCanvasRunEntity entity = runId == null || runId.isBlank()
                ? null : runMapper.selectById(runId);
        if (entity == null) return null;
        Map<String, Object> payload = result == null ? Map.of() : result;
        entity.setProgress(integer(payload.get("progress"), entity.getProgress()));
        entity.setNodeCount(integer(payload.get("nodeCount"), entity.getNodeCount()));
        entity.setEdgeCount(integer(payload.get("edgeCount"), entity.getEdgeCount()));
        entity.setRevision(integer(payload.get("revision"), entity.getRevision()));
        if (!Boolean.FALSE.equals(payload.get("ok"))) {
            entity.setStatus(RUNNING);
        }
        runMapper.updateById(entity);

        AgentMessage message = entity.getMessageId() == null
                ? null : messageMapper.selectById(entity.getMessageId());
        if (message != null) {
            Map<String, Object> metadata = new LinkedHashMap<>(
                    message.getMetadata() == null ? Map.of() : message.getMetadata());
            metadata.put("status", entity.getStatus());
            metadata.put("progress", entity.getProgress());
            copyInteger(payload, metadata, "revision");
            copyInteger(payload, metadata, "nodeCount");
            copyInteger(payload, metadata, "edgeCount");
            if (payload.get("error") != null) metadata.put("error", payload.get("error"));
            message.setMetadata(metadata);
            messageMapper.updateById(message);
        }
        return view(entity);
    }

    @Transactional
    public Map<String, Object> recordGraphResult(String runId,
                                                 String operationKey,
                                                 Map<String, Object> result) {
        Map<String, Object> existing = findOperationResult(runId, operationKey);
        if (existing != null) return existing;
        updateFromGraphResult(runId, result);
        try {
            return rememberOperationInternal(runId, operationKey, result);
        } catch (DuplicateKeyException duplicate) {
            Map<String, Object> winner = findOperationResult(runId, operationKey);
            return winner == null ? (result == null ? Map.of() : result) : winner;
        }
    }

    @Transactional
    public Map<String, Object> rememberOperation(String runId,
                                                 String operationKey,
                                                 Map<String, Object> result) {
        Map<String, Object> existing = findOperationResult(runId, operationKey);
        if (existing != null) return existing;

        return rememberOperationInternal(runId, operationKey, result);
    }

    private Map<String, Object> rememberOperationInternal(String runId,
                                                          String operationKey,
                                                          Map<String, Object> result) {
        AgentCanvasRunOperationEntity operation = new AgentCanvasRunOperationEntity();
        operation.setRunId(runId);
        operation.setOperationKey(operationKey);
        operation.setResultJson(writeMap(result));
        operationMapper.insert(operation);
        return result == null ? Map.of() : result;
    }

    public Map<String, Object> findOperationResult(String runId, String operationKey) {
        if (runId == null || runId.isBlank()
                || operationKey == null || operationKey.isBlank()) {
            return null;
        }
        AgentCanvasRunOperationEntity existing = operationMapper.selectOne(
                new LambdaQueryWrapper<AgentCanvasRunOperationEntity>()
                        .eq(AgentCanvasRunOperationEntity::getRunId, runId)
                        .eq(AgentCanvasRunOperationEntity::getOperationKey, operationKey)
                        .last("LIMIT 1"));
        return existing == null ? null : readMap(existing.getResultJson());
    }

    @Transactional
    public CanvasRunView complete(String runId, Map<String, Object> summary) {
        AgentCanvasRunEntity entity = runId == null || runId.isBlank()
                ? null : runMapper.selectById(runId);
        if (entity == null) return null;
        entity.setStatus(COMPLETED);
        entity.setProgress(100);
        entity.setNodeCount(integer(summary == null ? null : summary.get("nodeCount"), entity.getNodeCount()));
        entity.setEdgeCount(integer(summary == null ? null : summary.get("edgeCount"), entity.getEdgeCount()));
        entity.setRevision(integer(summary == null ? null : summary.get("revision"), entity.getRevision()));
        entity.setCompletedAt(OffsetDateTime.now());
        entity.setError(null);
        runMapper.updateById(entity);
        updateMessage(entity, summary, COMPLETED, 100);
        return view(entity);
    }

    @Transactional
    public CanvasRunView fail(String runId, String error) {
        AgentCanvasRunEntity entity = runId == null || runId.isBlank()
                ? null : runMapper.selectById(runId);
        if (entity == null) return null;
        entity.setStatus(FAILED);
        entity.setError(error == null || error.isBlank() ? "画布任务失败" : error);
        entity.setCompletedAt(OffsetDateTime.now());
        runMapper.updateById(entity);
        updateMessage(entity, Map.of("error", entity.getError()), FAILED, entity.getProgress());
        return view(entity);
    }

    private static CanvasRunView view(AgentCanvasRunEntity entity) {
        return new CanvasRunView(
                entity.getId(),
                entity.getConversationId(),
                entity.getProjectId(),
                entity.getTurnId(),
                entity.getCanvasId(),
                entity.getTitle(),
                entity.getStatus(),
                entity.getProgress() == null ? 0 : entity.getProgress(),
                entity.getNodeCount() == null ? 0 : entity.getNodeCount(),
                entity.getEdgeCount() == null ? 0 : entity.getEdgeCount(),
                entity.getRevision() == null ? 0 : entity.getRevision(),
                readMaps(entity.getPlanJson()),
                entity.getError(),
                entity.getCreatedAt(),
                entity.getCompletedAt());
    }

    private void updateMessage(AgentCanvasRunEntity entity,
                               Map<String, Object> payload,
                               String status,
                               int progress) {
        AgentMessage message = entity.getMessageId() == null
                ? null : messageMapper.selectById(entity.getMessageId());
        if (message == null) return;
        Map<String, Object> metadata = new LinkedHashMap<>(
                message.getMetadata() == null ? Map.of() : message.getMetadata());
        Map<String, Object> values = payload == null ? Map.of() : payload;
        metadata.put("status", status);
        metadata.put("progress", progress);
        copyInteger(values, metadata, "revision");
        copyInteger(values, metadata, "nodeCount");
        copyInteger(values, metadata, "edgeCount");
        if (entity.getError() != null && !entity.getError().isBlank()) {
            metadata.put("error", entity.getError());
        }
        message.setMetadata(metadata);
        messageMapper.updateById(message);
    }

    private static List<Map<String, Object>> readMaps(String raw) {
        try {
            return JSON.readValue(raw == null ? "[]" : raw,
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception ignored) {
            return "[]";
        }
    }

    private static String writeMap(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private static Map<String, Object> readMap(String raw) {
        try {
            return JSON.readValue(raw == null ? "{}" : raw,
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static int integer(Object value, Integer fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? (fallback == null ? 0 : fallback)
                    : Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback == null ? 0 : fallback;
        }
    }

    private static void copyInteger(Map<String, Object> source,
                                    Map<String, Object> target,
                                    String key) {
        if (source.get(key) instanceof Number number) target.put(key, number.intValue());
    }

    private static String normalizeTitle(String title) {
        return title == null || title.isBlank() ? "AI 设计画布" : title.trim();
    }

    public record CanvasRunView(
            String runId,
            String conversationId,
            String projectId,
            String turnId,
            String canvasId,
            String title,
            String status,
            int progress,
            int nodeCount,
            int edgeCount,
            int revision,
            List<Map<String, Object>> plan,
            String error,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("kind", "canvas-run");
            out.put("canvasRunId", runId);
            out.put("conversationId", conversationId);
            out.put("projectId", projectId);
            out.put("turnId", turnId);
            out.put("canvasId", canvasId);
            out.put("title", title);
            out.put("status", status);
            out.put("progress", progress);
            out.put("nodeCount", nodeCount);
            out.put("edgeCount", edgeCount);
            out.put("revision", revision);
            out.put("plan", plan);
            if (error != null && !error.isBlank()) out.put("error", error);
            return out;
        }
    }
}
