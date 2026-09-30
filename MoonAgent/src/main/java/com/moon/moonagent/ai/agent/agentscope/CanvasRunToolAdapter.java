package com.moon.moonagent.ai.agent.agentscope;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adds the active Canvas Run scope to server-side graph MCP calls and records
 * operation results without ever pausing the chat turn.
 */
final class CanvasRunToolAdapter extends ToolBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolCallback callback;
    private final AgentCanvasRunService runs;

    CanvasRunToolAdapter(ToolCallback callback, AgentCanvasRunService runs) {
        super(ToolBase.builder()
                .name(callback.getToolDefinition().name())
                .description(callback.getToolDefinition().description())
                .inputSchema(parseSchema(callback.getToolDefinition().inputSchema()))
                .readOnly(false)
                .concurrencySafe(false));
        this.callback = callback;
        this.runs = runs;
    }

    @Override
    public reactor.core.publisher.Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return reactor.core.publisher.Mono.fromCallable(() -> {
            AgentScopeRunContext context = context(param);
            AgentScopeTurnState turnState = context == null ? null : context.turnState();
            String runId = turnState == null ? null : turnState.activeCanvasRunId();
            String canvasId = turnState == null ? null : turnState.activeCanvasId();
            if ((runId == null || canvasId == null)
                    && context != null && context.request() != null) {
                AgentCanvasRunService.CanvasRunView active =
                        runs.activeByTurn(context.request().getTurnId());
                if (active != null) {
                    runId = active.runId();
                    canvasId = active.canvasId();
                    if (turnState != null) {
                        turnState.setActiveCanvasRun(runId, canvasId);
                    }
                }
            }
            if (context == null || context.conversation() == null
                    || runId == null || canvasId == null) {
                return result(param, Map.of(
                        "ok", false,
                        "code", "CANVAS_RUN_REQUIRED",
                        "message", "请先调用 canvas_create_run 创建画布任务"));
            }

            String operationKey = runId + ":" + toolCallId(param);
            Map<String, Object> previous = runs.findOperationResult(runId, operationKey);
            if (previous != null) return result(param, previous);

            Map<String, Object> input = new LinkedHashMap<>(
                    param.getInput() == null ? Map.of() : param.getInput());
            input.put("userId", context.conversation().getUserId());
            input.put("projectId", context.conversation().getProjectId());
            input.put("canvasId", canvasId);
            input.put("runId", runId);
            input.put("operationKey", operationKey);

            String output = callback.call(write(input));
            Map<String, Object> parsed = parseOutput(output);
            if (!parsed.containsKey("progress")) {
                parsed.put("progress", progressFor(getName()));
            }
            if (Boolean.FALSE.equals(parsed.get("ok"))) {
                runs.fail(runId, String.valueOf(parsed.getOrDefault(
                        "message", parsed.getOrDefault("error", "画布图命令失败"))));
            } else {
                runs.updateFromGraphResult(runId, parsed);
            }
            runs.rememberOperation(runId, operationKey, parsed);
            return result(param, parsed);
        });
    }

    private static ToolResultBlock result(ToolCallParam param, Map<String, Object> payload) {
        return ToolResultBlock.of(
                param.getToolUseBlock().getId(),
                param.getToolUseBlock().getName(),
                TextBlock.builder().text(write(payload)).build());
    }

    private static AgentScopeRunContext context(ToolCallParam param) {
        return param == null || param.getRuntimeContext() == null
                ? null : param.getRuntimeContext().get(AgentScopeRunContext.class);
    }

    private static String toolCallId(ToolCallParam param) {
        return param == null || param.getToolUseBlock() == null
                ? "" : param.getToolUseBlock().getId();
    }

    private static Map<String, Object> parseOutput(String output) {
        if (output == null || output.isBlank()) {
            return new LinkedHashMap<>(Map.of("ok", true));
        }
        try {
            JsonNode root = unwrapJson(JSON.readTree(output));
            if (root != null && root.isObject()) {
                return new LinkedHashMap<>(JSON.convertValue(root, new TypeReference<>() {}));
            }
            return new LinkedHashMap<>(Map.of("ok", true, "value", output));
        } catch (Exception ignored) {
            return new LinkedHashMap<>(Map.of("ok", true, "value", output));
        }
    }

    private static JsonNode unwrapJson(JsonNode root) throws Exception {
        JsonNode node = root;
        for (int depth = 0; node != null && depth < 8; depth++) {
            if (node.isArray()) {
                if (node.isEmpty()) return node;
                node = node.get(0);
                continue;
            }
            if (node.isTextual()) {
                node = JSON.readTree(node.asText());
                continue;
            }
            if (node.isObject() && node.has("text")) {
                node = node.path("text");
                continue;
            }
            if (node.isObject() && node.path("content").isArray()
                    && !node.path("content").isEmpty()) {
                node = node.path("content");
                continue;
            }
            break;
        }
        return node;
    }

    private static int progressFor(String toolName) {
        if (toolName.endsWith("_layout_graph")) return 90;
        if (toolName.endsWith("_create_result_slots")
                || toolName.endsWith("_mark_result_slots")) return 80;
        if (toolName.endsWith("_create_edge")) return 65;
        if (toolName.endsWith("_import_media")) return 50;
        if (toolName.endsWith("_create_nodes")) return 40;
        if (toolName.endsWith("_create_node") || toolName.endsWith("_update_node")) return 30;
        return 20;
    }

    private static Map<String, Object> parseSchema(String schema) {
        if (schema == null || schema.isBlank()) {
            return Map.of("type", "object", "properties", Map.of());
        }
        try {
            return JSON.readValue(schema, new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of("type", "object", "properties", Map.of());
        }
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ignored) {
            return "{}";
        }
    }
}
