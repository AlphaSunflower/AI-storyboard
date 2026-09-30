package com.moon.moonagent.ai.agent.agentscope;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.entity.AgentCanvasSessionEntity;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.MorrowCanvas;
import com.moon.moonagent.mapper.AgentCanvasSessionMapper;
import com.moon.moonagent.service.MorrowCanvasService;
import com.storyboard.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AgentCanvasSessionService {

    static final String PENDING_SELECTION = "pending_selection";
    static final String PENDING_OPEN = "pending_open";
    static final String OPENING = "opening";
    static final String READY = "ready";
    static final String RESUMED = "resumed";
    static final String CANCELLED = "cancelled";
    static final String EXPIRED = "expired";
    static final String FAILED = "failed";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentCanvasSessionMapper mapper;
    private final MorrowCanvasService canvasService;

    @Transactional
    public CanvasSessionView create(AgentConversation conversation,
                                    String turnId,
                                    String replyId,
                                    String toolCallId,
                                    AgentScopeCanvasTools.SessionPreparation preparation,
                                    List<Map<String, Object>> mediaRefs) {
        String status = preparation.canvasId() == null || preparation.canvasId().isBlank()
                ? PENDING_SELECTION : PENDING_OPEN;
        AgentCanvasSessionEntity entity = new AgentCanvasSessionEntity();
        entity.setConversationId(conversation.getId());
        entity.setUserId(conversation.getUserId());
        entity.setProjectId(preparation.projectId());
        entity.setTurnId(turnId);
        entity.setRuntimeReplyId(replyId);
        entity.setRuntimeToolCallId(toolCallId);
        entity.setCanvasId(blankToNull(preparation.canvasId()));
        entity.setCanvasTitle(preparation.canvasTitle());
        entity.setIntent(preparation.intent());
        entity.setStatus(status);
        entity.setChoicesJson(write(preparation.choices()));
        entity.setMediaRefsJson(write(mediaRefs == null ? List.of() : mediaRefs));
        entity.setExpiresAt(OffsetDateTime.now().plusMinutes(30));
        mapper.insert(entity);
        return view(entity);
    }

    @Transactional
    public CanvasSessionView ready(String userId, String conversationId, String sessionId,
                                   String selectedCanvasId, boolean createNew) {
        AgentCanvasSessionEntity entity = owned(userId, conversationId, sessionId);
        if (READY.equals(entity.getStatus())) {
            return view(entity);
        }
        if (!List.of(PENDING_SELECTION, PENDING_OPEN, OPENING).contains(entity.getStatus())) {
            throw new BusinessException(40902, "画布会话当前状态不可打开");
        }
        if (entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(OffsetDateTime.now())) {
            entity.setStatus(EXPIRED);
            mapper.updateById(entity);
            throw new BusinessException(40902, "画布会话已过期，请重新发起");
        }
        MorrowCanvas target = null;
        if (createNew) {
            target = canvasService.create(userId, entity.getProjectId(), "AI 设计画布");
        } else if (selectedCanvasId != null && !selectedCanvasId.isBlank()) {
            target = canvasService.listByProject(userId, entity.getProjectId()).stream()
                    .filter(canvas -> selectedCanvasId.equals(canvas.getId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(40401, "目标画布不存在"));
        } else if (entity.getCanvasId() != null && !entity.getCanvasId().isBlank()) {
            target = canvasService.get(userId, entity.getCanvasId());
        } else {
            throw new BusinessException(40001, "请选择要打开的画布");
        }
        entity.setCanvasId(target.getId());
        entity.setCanvasTitle(target.getTitle() == null ? "画布" : target.getTitle());
        entity.setStatus(READY);
        entity.setReadyAt(OffsetDateTime.now());
        entity.setError(null);
        mapper.updateById(entity);
        return view(entity);
    }

    @Transactional
    public CanvasSessionView cancel(String userId, String conversationId, String sessionId) {
        AgentCanvasSessionEntity entity = owned(userId, conversationId, sessionId);
        if (!RESUMED.equals(entity.getStatus())) {
            entity.setStatus(CANCELLED);
            mapper.updateById(entity);
        }
        return view(entity);
    }

    public CanvasSessionView pending(String userId, String conversationId) {
        AgentCanvasSessionEntity entity = mapper.selectOne(new LambdaQueryWrapper<AgentCanvasSessionEntity>()
                .eq(AgentCanvasSessionEntity::getUserId, userId)
                .eq(AgentCanvasSessionEntity::getConversationId, conversationId)
                .in(AgentCanvasSessionEntity::getStatus,
                        List.of(PENDING_SELECTION, PENDING_OPEN, OPENING, READY))
                .orderByDesc(AgentCanvasSessionEntity::getCreatedAt)
                .last("LIMIT 1"));
        return entity == null ? null : view(entity);
    }

    @Transactional
    public void markResumed(String sessionId) {
        AgentCanvasSessionEntity entity = mapper.selectById(sessionId);
        if (entity == null) return;
        entity.setStatus(RESUMED);
        entity.setResumedAt(OffsetDateTime.now());
        mapper.updateById(entity);
    }

    @Transactional
    public void markFailed(String sessionId, String error) {
        AgentCanvasSessionEntity entity = mapper.selectById(sessionId);
        if (entity == null) return;
        entity.setStatus(FAILED);
        entity.setError(error == null ? "画布会话失败" : error);
        mapper.updateById(entity);
    }

    @Transactional
    public void markRetryable(String sessionId, String error) {
        AgentCanvasSessionEntity entity = mapper.selectById(sessionId);
        if (entity == null || RESUMED.equals(entity.getStatus())) return;
        entity.setStatus(READY);
        entity.setError(error == null ? "画布会话暂时失败，请重试" : error);
        entity.setReadyAt(entity.getReadyAt() == null ? OffsetDateTime.now() : entity.getReadyAt());
        mapper.updateById(entity);
    }

    public AgentCanvasSessionEntity owned(String userId, String conversationId, String sessionId) {
        AgentCanvasSessionEntity entity = mapper.selectById(sessionId);
        if (entity == null || !userId.equals(entity.getUserId())
                || !conversationId.equals(entity.getConversationId())) {
            throw new BusinessException(40401, "画布会话不存在或无权访问");
        }
        return entity;
    }

    private CanvasSessionView view(AgentCanvasSessionEntity entity) {
        return new CanvasSessionView(
                entity.getId(),
                entity.getConversationId(),
                entity.getTurnId(),
                entity.getRuntimeToolCallId(),
                entity.getProjectId(),
                entity.getCanvasId(),
                entity.getCanvasTitle(),
                entity.getIntent(),
                entity.getStatus(),
                readChoices(entity.getChoicesJson()),
                readMaps(entity.getMediaRefsJson()),
                entity.getExpiresAt());
    }

    private static List<AgentScopeCanvasTools.CanvasChoice> readChoices(String raw) {
        try {
            return JSON.readValue(raw == null ? "[]" : raw,
                    new TypeReference<List<AgentScopeCanvasTools.CanvasChoice>>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static List<Map<String, Object>> readMaps(String raw) {
        try {
            return JSON.readValue(raw == null ? "[]" : raw,
                    new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record CanvasSessionView(
            String sessionId,
            String conversationId,
            String turnId,
            String runtimeToolCallId,
            String projectId,
            String canvasId,
            String canvasTitle,
            String intent,
            String status,
            List<AgentScopeCanvasTools.CanvasChoice> choices,
            List<Map<String, Object>> mediaRefs,
            OffsetDateTime expiresAt
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("sessionId", sessionId);
            value.put("conversationId", conversationId);
            value.put("turnId", turnId);
            value.put("runtimeToolCallId", runtimeToolCallId);
            value.put("projectId", projectId);
            if (canvasId != null) value.put("canvasId", canvasId);
            if (canvasTitle != null) value.put("canvasTitle", canvasTitle);
            value.put("intent", intent == null ? "" : intent);
            value.put("status", status);
            value.put("choices", choices == null ? List.of() : choices);
            value.put("mediaRefs", mediaRefs == null ? List.of() : mediaRefs);
            value.put("expiresAt", expiresAt == null ? "" : expiresAt.toString());
            return value;
        }
    }

    public static List<Map<String, Object>> mediaRefMaps(List<com.moon.moonagent.ai.agent.CanvasMediaRef> refs) {
        if (refs == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (com.moon.moonagent.ai.agent.CanvasMediaRef ref : refs) {
            if (ref == null) continue;
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("kind", ref.kind());
            if (ref.assetId() != null) value.put("assetId", ref.assetId());
            if (ref.url() != null) value.put("url", ref.url());
            if (ref.name() != null) value.put("name", ref.name());
            if (ref.mimeType() != null) value.put("mimeType", ref.mimeType());
            if (ref.source() != null) value.put("source", ref.source());
            out.add(value);
        }
        return out;
    }
}
