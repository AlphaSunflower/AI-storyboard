package com.moon.moonagent.ai.agent.agentscope;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.mapper.AgentAssetMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class AgentScopeSessionTools {

    public static final String LIST_SESSION_ASSETS = "list_session_assets";
    public static final String RESOLVE_REFERENCE_ASSETS = "resolve_reference_assets";
    public static final String GET_TASK_STATUS = "get_generation_task_status";
    public static final String LIST_CONVERSATION_TASKS = "list_conversation_tasks";
    public static final String GET_AGENT_TASK = "get_agent_task";

    private final SessionAssetService sessionAssets;
    private final AgentAssetMapper assetMapper;
    private final AgentScopeAsyncTaskService asyncTasks;

    public List<AgentScopeTool> tools() {
        return List.of(
                new AgentScopeTool(
                        LIST_SESSION_ASSETS,
                        "List recent assets that belong to the current conversation.",
                        AgentScopeTool.schema(Map.of(
                                "type", Map.of("type", "string"),
                                "limit", Map.of("type", "string")), List.of()),
                        true, true, false,
                        (input, param) -> listSessionAssets(
                                string(input.get("type")), string(input.get("limit")), context(param))),
                new AgentScopeTool(
                        RESOLVE_REFERENCE_ASSETS,
                        "Resolve the most relevant prior conversation image or artifact for a "
                                + "referential request such as use the last image or continue that design.",
                        AgentScopeTool.schema(Map.of(
                                "query", Map.of("type", "string"),
                                "type", Map.of("type", "string"),
                                "limit", Map.of("type", "string"),
                                "referential", Map.of("type", "boolean")), List.of()),
                        true, true, false,
                        (input, param) -> resolveReferenceAssets(
                                string(input.get("query")), string(input.get("type")),
                                string(input.get("limit")),
                                Boolean.TRUE.equals(input.get("referential")), context(param))),
                new AgentScopeTool(
                        GET_TASK_STATUS,
                        "Return the current status of an asynchronous generation task.",
                        AgentScopeTool.schema(Map.of(
                                "task_id", Map.of("type", "string")), List.of("task_id")),
                        true, true, false,
                        (input, param) -> getGenerationTaskStatus(
                                string(input.get("task_id")), context(param))),
                new AgentScopeTool(
                        LIST_CONVERSATION_TASKS,
                        "List background tasks for the current conversation, including status and stage.",
                        AgentScopeTool.schema(Map.of(
                                "status", Map.of("type", "string")), List.of()),
                        true, true, false,
                        (input, param) -> listConversationTasks(
                                string(input.get("status")), context(param))),
                new AgentScopeTool(
                        GET_AGENT_TASK,
                        "Get details and result summary for one background task in the current conversation.",
                        AgentScopeTool.schema(Map.of(
                                "task_id", Map.of("type", "string")), List.of("task_id")),
                        true, true, false,
                        (input, param) -> getAgentTask(
                                string(input.get("task_id")), context(param))));
    }

    public List<SessionAssetRef> listSessionAssets(
            String type,
            String limit,
            AgentScopeRunContext context) {
        return sessionAssets.list(
                requireConversation(context).getId(), type, positiveInt(limit, 0), false);
    }

    public List<SessionAssetRef> resolveReferenceAssets(
            String query,
            String type,
            String limit,
            Boolean referential,
            AgentScopeRunContext context) {
        List<SessionAssetRef> refs = sessionAssets.resolve(
                requireConversation(context).getId(), type, query,
                positiveInt(limit, 0), Boolean.TRUE.equals(referential));
        if (!refs.isEmpty() && context.request() != null) {
            Map<String, Object> metadata = new LinkedHashMap<>(context.request().getTurnMetadata());
            metadata.put("referenceAssets", refs);
            context.request().setTurnMetadata(metadata);
        }
        return refs;
    }

    public Map<String, Object> getGenerationTaskStatus(
            String taskId,
            AgentScopeRunContext context) {
        String conversationId = requireConversation(context).getId();
        Map<String, Object> asyncStatus = asyncTasks.getConversationStatus(conversationId, taskId);
        if (!asyncStatus.isEmpty()) return asyncStatus;
        AgentAsset asset = assetMapper.selectOne(new LambdaQueryWrapper<AgentAsset>()
                .eq(AgentAsset::getTaskId, taskId)
                .eq(AgentAsset::getConversationId, conversationId)
                .last("LIMIT 1"));
        if (asset == null) return Map.of("taskId", taskId, "status", "not_found");
        return Map.of(
                "taskId", taskId,
                "kind", "video",
                "assetId", asset.getId(),
                "status", asset.getStatus() == null ? "unknown" : asset.getStatus(),
                "url", asset.getUrl() == null ? "" : asset.getUrl(),
                "error", asset.getError() == null ? "" : asset.getError());
    }

    public List<com.moon.moonagent.ai.agent.task.AgentTaskView> listConversationTasks(
            String status,
            AgentScopeRunContext context) {
        var conversation = requireConversation(context);
        return asyncTasks.listTaskViews(
                conversation.getUserId(), conversation.getId(), conversation.getProjectId(), status, 50);
    }

    public com.moon.moonagent.ai.agent.task.AgentTaskView getAgentTask(
            String taskId,
            AgentScopeRunContext context) {
        return asyncTasks.getConversationTask(requireConversation(context).getId(), taskId);
    }

    static com.moon.moonagent.entity.AgentConversation requireConversation(AgentScopeRunContext context) {
        if (context == null || context.conversation() == null) {
            throw new IllegalStateException("AgentScope 运行上下文缺失");
        }
        return context.conversation();
    }

    static int positiveInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static AgentScopeRunContext context(io.agentscope.core.tool.ToolCallParam param) {
        return param.getRuntimeContext() == null
                ? null : param.getRuntimeContext().get(AgentScopeRunContext.class);
    }
}
