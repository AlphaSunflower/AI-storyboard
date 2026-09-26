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
        AgentTurnEntity turn = new AgentTurnEntity();
        turn.setConversationId(conversation.getId());
        turn.setStatus("running");
        turn.setRuntimeEngine("agentscope-java-2.0.3");
        turn.setInputText(input == null ? "" : input);
        turn.setLastSequence(0);
        turn.setStartedAt(OffsetDateTime.now());
        turnMapper.insert(turn);
        return turn;
    }

    public synchronized AgentTurnEventEnvelope append(String turnId, String type, Object payload) {
        AgentTurnEntity turn = turnMapper.selectById(turnId);
        if (turn == null) {
            throw new IllegalArgumentException("turn not found: " + turnId);
        }
        int sequence = (turn.getLastSequence() == null ? 0 : turn.getLastSequence()) + 1;
        AgentTurnEventEntity event = new AgentTurnEventEntity();
        event.setTurnId(turnId);
        event.setSequence(sequence);
        event.setEventType(type);
        event.setPayload(toJson(payload));
        event.setCreatedAt(OffsetDateTime.now());
        eventMapper.insert(event);

        turn.setLastSequence(sequence);
        turnMapper.updateById(turn);
        return new AgentTurnEventEnvelope(turnId, sequence, type, payloadMap(payload));
    }

    public AgentTurnEntity find(String turnId) {
        return turnId == null || turnId.isBlank() ? null : turnMapper.selectById(turnId);
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
                        fromJson(event.getPayload())))
                .toList();
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
