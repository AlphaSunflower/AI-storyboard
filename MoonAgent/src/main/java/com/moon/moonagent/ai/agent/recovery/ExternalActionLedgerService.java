package com.moon.moonagent.ai.agent.recovery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.entity.AgentExternalAction;
import com.moon.moonagent.mapper.AgentExternalActionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * At-least-once recovery guard for externally visible agent actions. The same
 * checkpoint/tool invocation is never sent to a provider twice after its
 * outcome is known.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalActionLedgerService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final AgentExternalActionMapper mapper;

    public Map<String, Object> executeOnce(String conversationId,
                                           String checkpointId,
                                           String toolCallId,
                                           String toolName,
                                           String actionType,
                                           Map<String, Object> request,
                                           Supplier<Map<String, Object>> invocation) {
        if (checkpointId == null || checkpointId.isBlank()) {
            return invocation.get();
        }
        String stableToolCallId = toolCallId == null || toolCallId.isBlank()
                ? "unknown" : toolCallId;
        String actionKey = checkpointId + ":" + stableToolCallId + ":" + toolName;
        AgentExternalAction existing = mapper.selectByActionKey(actionKey);
        if (existing == null) {
            AgentExternalAction created = new AgentExternalAction();
            created.setActionKey(actionKey);
            created.setConversationId(conversationId);
            created.setCheckpointId(checkpointId);
            created.setToolCallId(stableToolCallId);
            created.setToolName(toolName);
            created.setActionType(actionType);
            created.setRequestHash(hash(request));
            created.setStatus("PREPARED");
            created.setAttempt(1);
            try {
                mapper.insert(created);
            } catch (DuplicateKeyException duplicate) {
                existing = mapper.selectByActionKey(actionKey);
            }
        }
        if (existing != null) {
            Map<String, Object> replay = replay(existing);
            if (replay != null) return replay;
        }
        if (mapper.markSent(actionKey) == 0) {
            AgentExternalAction current = mapper.selectByActionKey(actionKey);
            Map<String, Object> replay = replay(current);
            return replay == null ? unknown(actionKey, "外部动作状态不可确认") : replay;
        }
        try {
            Map<String, Object> result = invocation.get();
            String responseJson = serialize(result);
            String providerTaskId = string(result.get("taskId"));
            String terminal = providerTaskId.isBlank() ? "COMPLETED" : "ACCEPTED";
            mapper.finish(actionKey, terminal, providerTaskId, responseJson, null);
            return result;
        } catch (Exception e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            mapper.finish(actionKey, "UNKNOWN", null, null, truncate(error));
            log.warn("external action outcome unknown actionKey={} tool={} error={}",
                    actionKey, toolName, error);
            return unknown(actionKey, error);
        }
    }

    private static Map<String, Object> replay(AgentExternalAction action) {
        if (action == null) return null;
        if (!"COMPLETED".equals(action.getStatus()) && !"ACCEPTED".equals(action.getStatus())) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (action.getResponseJson() != null && !action.getResponseJson().isBlank()) {
            try {
                result.putAll(JSON.readValue(action.getResponseJson(), MAP_TYPE));
            } catch (Exception e) {
                return unknown(action.getActionKey(), "外部动作结果无法解析");
            }
        }
        result.put("replayed", true);
        result.put("externalActionKey", action.getActionKey());
        if (action.getProviderTaskId() != null && !action.getProviderTaskId().isBlank()) {
            result.put("taskId", action.getProviderTaskId());
        }
        return result;
    }

    private static Map<String, Object> unknown(String actionKey, String error) {
        return Map.of(
                "ok", false,
                "status", "unknown",
                "externalActionKey", actionKey,
                "error", "外部任务已提交但状态无法确认，为避免重复扣费不会自动重试。"
                        + (error == null || error.isBlank() ? "" : " 原因：" + error));
    }

    private static String serialize(Map<String, Object> result) {
        try {
            return JSON.writeValueAsString(result == null ? Map.of() : result);
        } catch (Exception e) {
            throw new IllegalStateException("外部动作结果序列化失败", e);
        }
    }

    private static String hash(Map<String, Object> request) {
        try {
            byte[] bytes = JSON.writeValueAsBytes(request == null ? Map.of() : request);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte value : digest) out.append(String.format("%02x", value));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("外部动作请求摘要失败", e);
        }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }
}
