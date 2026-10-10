package com.moon.moonagent.ai.agent.turn;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentTurnEntity;
import com.moon.moonagent.entity.AgentTurnEventEntity;
import com.moon.moonagent.mapper.AgentTurnEventMapper;
import com.moon.moonagent.mapper.AgentTurnMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AgentTurnEventStore {

    private static final Logger log = LoggerFactory.getLogger(AgentTurnEventStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentTurnMapper turnMapper;
    private final AgentTurnEventMapper eventMapper;

    public AgentTurnEntity start(AgentConversation conversation, String input) {
        return start(conversation, input, null);
    }

    public AgentTurnEntity start(AgentConversation conversation, String input, String clientRequestId) {
        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setConversationId(conversation.getId());
        turn.setStatus("running");
        turn.setRuntimeEngine("agentscope-java-2.0.3");
        turn.setInputText(input == null ? "" : input);
        turn.setClientRequestId(clientRequestId == null || clientRequestId.isBlank()
                ? null : clientRequestId);
        turn.setLastSequence(0);
        turn.setStartedAt(OffsetDateTime.now());
        turnMapper.insert(turn);
        return turn;
    }

    public AgentTurnEntity findByClientRequestId(String conversationId, String clientRequestId) {
        if (conversationId == null || conversationId.isBlank()
                || clientRequestId == null || clientRequestId.isBlank()) {
            return null;
        }
        return turnMapper.selectOne(new LambdaQueryWrapper<AgentTurnEntity>()
                .eq(AgentTurnEntity::getConversationId, conversationId)
                .eq(AgentTurnEntity::getClientRequestId, clientRequestId)
                .last("LIMIT 1"));
    }

    public AgentTurnEventEnvelope append(String turnId, String type, Object payload) {
        return appendBatch(turnId, List.of(new EventDraft(type, payload))).getFirst();
    }

    @Transactional
    public List<AgentTurnEventEnvelope> appendBatch(String turnId, List<EventDraft> drafts) {
        if (turnId == null || turnId.isBlank() || drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        Integer lastSequence = turnMapper.incrementSequenceBy(turnId, drafts.size());
        if (lastSequence == null) {
            throw new IllegalArgumentException("turn not found: " + turnId);
        }
        int firstSequence = lastSequence - drafts.size() + 1;
        OffsetDateTime now = OffsetDateTime.now();
        List<AgentTurnEventEntity> events = new java.util.ArrayList<>(drafts.size());
        List<AgentTurnEventEnvelope> envelopes = new java.util.ArrayList<>(drafts.size());
        for (int index = 0; index < drafts.size(); index++) {
            EventDraft draft = drafts.get(index);
            int sequence = firstSequence + index;
            String payloadJson = toJson(draft.payload());
            AgentTurnEventEntity event = new AgentTurnEventEntity();
            event.setTurnId(turnId);
            event.setSequence(sequence);
            event.setEventType(draft.type());
            event.setPayload(payloadJson);
            event.setCreatedAt(now);
            events.add(event);
            envelopes.add(new AgentTurnEventEnvelope(
                    turnId, sequence, draft.type(), payloadMap(draft.payload()), payloadJson));
        }
        eventMapper.insertBatch(events);
        return List.copyOf(envelopes);
    }

    public AgentTurnEntity find(String turnId) {
        return turnId == null || turnId.isBlank() ? null : turnMapper.selectById(turnId);
    }

    public AgentTurnEntity findActive(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return null;
        return turnMapper.selectOne(new LambdaQueryWrapper<AgentTurnEntity>()
                .eq(AgentTurnEntity::getConversationId, conversationId)
                .in(AgentTurnEntity::getStatus, List.of("running", "waiting_user"))
                .orderByDesc(AgentTurnEntity::getStartedAt)
                .last("LIMIT 1"));
    }

    public AgentTurnEntity findByCheckpoint(String checkpointToken) {
        if (checkpointToken == null || checkpointToken.isBlank()) return null;
        return turnMapper.selectOne(new LambdaQueryWrapper<AgentTurnEntity>()
                .eq(AgentTurnEntity::getCheckpointId, checkpointToken)
                .orderByDesc(AgentTurnEntity::getStartedAt)
                .last("LIMIT 1"));
    }

    public AgentTurnEntity resume(String turnId) {
        AgentTurnEntity turn = find(turnId);
        if (turn == null) return null;
        turn.setStatus("running");
        turn.setError("");
        turn.setCompletedAt(null);
        turnMapper.updateById(turn);
        return turn;
    }

    public void attachCheckpoint(String turnId, String checkpointId) {
        AgentTurnEntity turn = turnMapper.selectById(turnId);
        if (turn == null) return;
        turn.setCheckpointId(checkpointId);
        turn.setStatus("waiting_user");
        turnMapper.updateById(turn);
    }

    public void markWaitingUser(String turnId) {
        AgentTurnEntity turn = turnMapper.selectById(turnId);
        if (turn == null) return;
        turn.setStatus("waiting_user");
        turnMapper.updateById(turn);
    }

    public void finish(String turnId, String status, String error, OffsetDateTime completedAt) {
        AgentTurnEntity turn = turnMapper.selectById(turnId);
        if (turn == null) return;
        turn.setStatus(status == null || status.isBlank() ? "completed" : status);
        turn.setError(error);
        turn.setCompletedAt(completedAt == null ? OffsetDateTime.now() : completedAt);
        turnMapper.updateById(turn);
    }

    public List<AgentTurnEventEnvelope> eventsAfter(String turnId, int afterSequence) {
        return eventMapper.selectList(new LambdaQueryWrapper<AgentTurnEventEntity>()
                        .eq(AgentTurnEventEntity::getTurnId, turnId)
                        .gt(AgentTurnEventEntity::getSequence, Math.max(0, afterSequence))
                        .orderByAsc(AgentTurnEventEntity::getSequence))
                .stream()
                .map(event -> new AgentTurnEventEnvelope(
                        turnId,
                        event.getSequence(),
                        event.getEventType(),
                        fromJson(event.getPayload()),
                        event.getPayload()))
                .toList();
    }

    public record EventDraft(String type, Object payload) {
    }

    private static String toJson(Object payload) {
        try {
            return JSON.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception e) {
            log.warn("turn event payload serialization failed: {}", e.getMessage());
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payloadMap(Object payload) {
        if (payload instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        try {
            return JSON.convertValue(payload, Map.class);
        } catch (Exception e) {
            return Map.of("value", String.valueOf(payload));
        }
    }

    private static Map<String, Object> fromJson(String payload) {
        try {
            return JSON.readValue(payload, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of("raw", payload == null ? "" : payload);
        }
    }
}
