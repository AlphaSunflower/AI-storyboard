package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.memory.AgentMemoryLimits;
import com.moon.moonagent.ai.agent.memory.AgentMemoryService;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMemory;
import com.storyboard.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目级长期记忆工具：显式 remember，按项目隔离 recall。
 *
 * <p>不做自动写：只有模型明确判断这是应跨会话保留的事实，才调用 remember_memory。
 */
@Component
@RequiredArgsConstructor
public class AgentScopeMemoryTools {

    public static final String REMEMBER_MEMORY = "remember_memory";
    public static final String RECALL_MEMORY = "recall_memory";

    private final AgentMemoryService memories;

    public List<AgentScopeTool> tools() {
        return List.of(remember(), recall());
    }

    private AgentScopeTool remember() {
        return new AgentScopeTool(
                REMEMBER_MEMORY,
                "Record a durable project fact that should survive future conversations "
                        + "(goal, visual style, hard constraint, confirmed decision, user feedback). "
                        + "Do not use for one-off instructions or transient chat. "
                        + "Project memory is agent-owned: the user can only delete entries, and the "
                        + "separate global memory is user-maintained and not writable here. "
                        + "Project memory is capped at " + AgentMemoryLimits.PROJECT_MAX
                        + " entries per project and " + AgentMemoryLimits.USER_MAX
                        + " per account; when a write is rejected, tell the user the exact reason "
                        + "(cap reached or memory disabled) instead of retrying or claiming success.",
                AgentScopeTool.schema(Map.of(
                        "kind", Map.of("type", "string",
                                "enum", List.of("goal", "style", "constraint", "decision", "feedback")),
                        "content", Map.of("type", "string"),
                        "confidence", Map.of("type", "number", "minimum", 0, "maximum", 1),
                        "expires_days", Map.of("type", "integer", "minimum", 1, "maximum", 3650)),
                        List.of("kind", "content")),
                false, true, false,
                (input, param) -> {
                    AgentScopeRunContext context = AgentScopeToolPipeline.context(param);
                    AgentConversation conversation = AgentScopeSessionTools.requireConversation(context);
                    int days = positiveInt(input.get("expires_days"), 0);
                    try {
                        AgentMemory memory = memories.remember(
                                conversation.getUserId(), conversation.getProjectId(), conversation.getId(),
                                text(input.get("kind")), text(input.get("content")),
                                "agent", number(input.get("confidence")),
                                days > 0 ? OffsetDateTime.now().plusDays(days) : null);
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("id", memory.getId());
                        result.put("projectId", memory.getProjectId());
                        result.put("kind", memory.getKind());
                        result.put("content", memory.getContent());
                        result.put("confidence", memory.getConfidence());
                        result.put("expiresAt", memory.getExpiresAt());
                        return result;
                    } catch (BusinessException | IllegalArgumentException error) {
                        // 容量/校验类拒绝必须是模型可见的结构化失败，不能静默吞掉后继续声称“已记住”。
                        return failure(conversation.getProjectId(), error.getMessage());
                    }
                });
    }

    private AgentScopeTool recall() {
        return new AgentScopeTool(
                RECALL_MEMORY,
                "Recall durable project facts for the current project. Results are project-scoped and "
                        + "expired memories are omitted.",
                AgentScopeTool.schema(Map.of(
                        "query", Map.of("type", "string"),
                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", 20)),
                        List.of()),
                true, true, false,
                (input, param) -> {
                    AgentScopeRunContext context = AgentScopeToolPipeline.context(param);
                    AgentConversation conversation = AgentScopeSessionTools.requireConversation(context);
                    if (!memories.enabled(conversation.getUserId())) {
                        return failure(conversation.getProjectId(),
                                "记忆管理已关闭，当前无法召回项目记忆");
                    }
                    List<AgentMemory> rows = memories.recall(
                            conversation.getProjectId(), text(input.get("query")),
                            positiveInt(input.get("limit"), 5));
                    return Map.of("projectId", conversation.getProjectId(),
                            "memories", rows.stream().map(AgentScopeMemoryTools::view).toList());
                });
    }

    private static Map<String, Object> failure(String projectId, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("code", "MEMORY_UNAVAILABLE");
        result.put("projectId", projectId);
        result.put("message", message == null || message.isBlank() ? "记忆当前不可用" : message);
        return result;
    }

    private static Map<String, Object> view(AgentMemory memory) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", memory.getId());
        value.put("kind", memory.getKind());
        value.put("content", memory.getContent());
        value.put("source", memory.getSource());
        value.put("confidence", memory.getConfidence());
        value.put("updatedAt", memory.getUpdatedAt());
        return value;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int positiveInt(Object value, int fallback) {
        if (value instanceof Number n) return Math.max(0, n.intValue());
        try {
            return Math.max(0, Integer.parseInt(String.valueOf(value)));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static Double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try {
            return value == null ? null : Double.parseDouble(String.valueOf(value));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
