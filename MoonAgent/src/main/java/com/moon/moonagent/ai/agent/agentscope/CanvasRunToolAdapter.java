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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adds the active Canvas Run scope to server-side graph MCP calls and records
 * operation results without ever pausing the chat turn.
 */
final class CanvasRunToolAdapter extends ToolBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 注入型参数：由本适配器在模型调用之后强制覆盖写入，模型永远不该看到或传它们。
     *
     * <p>MCP 侧声明必须保持 {@code required = false}（那边的 schema 测试兜底）；这里只负责
     * "模型可见的那一份 schema"不再包含它们：15 个画布工具 × 5 个属性会在每次模型调用上重复计费。
     */
    static final Set<String> INJECTED_PARAM_NAMES = Set.of(
            "userId", "projectId", "canvasId", "runId", "operationKey");

    private final ToolCallback callback;
    private final AgentCanvasRunService runs;

    CanvasRunToolAdapter(ToolCallback callback, AgentCanvasRunService runs) {
        this(callback, runs, true);
    }

    /**
     * @param hideInjectedParams 是否从模型可见 schema 中隐藏注入型参数（默认隐藏；
     *                           由 {@code ai.agent.agentscope.canvas-schema-hide-injected-params} 控制）
     */
    CanvasRunToolAdapter(ToolCallback callback, AgentCanvasRunService runs, boolean hideInjectedParams) {
        super(ToolBase.builder()
                .name(callback.getToolDefinition().name())
                .description(callback.getToolDefinition().description())
                .inputSchema(hideInjectedParams
                        ? hideInjectedParams(parseSchema(callback.getToolDefinition().inputSchema()))
                        : parseSchema(callback.getToolDefinition().inputSchema()))
                .readOnly(false)
                .concurrencySafe(false));
        this.callback = callback;
        this.runs = runs;
    }

    /**
     * 从 JSON Schema 中移除注入型参数：{@code properties} 里删键，{@code required} 里删名。
     *
     * <p>只认 {@link #INJECTED_PARAM_NAMES} 这 5 个名字，业务参数原样保留；其余 schema 键
     * （{@code type} 等）不动，保证仍是合法 JSON Schema。
     *
     * <p>真的删掉了属性时，把 {@code additionalProperties:false} 放宽成 {@code true}：
     * 模型偶尔仍会凭旧记忆传 {@code userId}，而声明里已经没有它——若继续严格禁止额外属性，
     * 这种"以前会被静默覆盖"的调用会变成硬失败（Parameter validation failed）。业务必填项
     * 仍由 {@code required} 兜底，放宽只有好处。
     */
    static Map<String, Object> hideInjectedParams(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return Map.of("type", "object", "properties", Map.of());
        }
        Map<String, Object> out = new LinkedHashMap<>(schema);
        boolean removed = false;
        Object properties = schema.get("properties");
        if (properties instanceof Map<?, ?> map) {
            Map<String, Object> filtered = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String name = String.valueOf(entry.getKey());
                if (INJECTED_PARAM_NAMES.contains(name)) {
                    removed = true;
                    continue;
                }
                filtered.put(name, entry.getValue());
            }
            out.put("properties", filtered);
        } else {
            out.put("properties", Map.of());
        }
        Object required = schema.get("required");
        if (required instanceof List<?> list) {
            out.put("required", list.stream()
                    .map(String::valueOf)
                    .filter(name -> !INJECTED_PARAM_NAMES.contains(name))
                    .toList());
        }
        if (removed && Boolean.FALSE.equals(schema.get("additionalProperties"))) {
            out.put("additionalProperties", true);
        }
        return out;
    }

    @Override
    public reactor.core.publisher.Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return reactor.core.publisher.Mono.fromCallable(() -> {
            AgentScopeRunContext context = context(param);
            AgentScopeTurnState turnState = context == null ? null : context.turnState();
            String runId = turnState == null ? null : turnState.activeCanvasRunId();
            String canvasId = turnState == null ? null : turnState.activeCanvasId();
            String turnId = context == null || context.request() == null
                    ? "" : context.request().getTurnId();
            if ((runId == null || canvasId == null)
                    && context != null && context.request() != null) {
                AgentCanvasRunService.CanvasRunView active = runs.activeByTurn(turnId);
                if (active != null) {
                    runId = active.runId();
                    canvasId = active.canvasId();
                    if (turnState != null) {
                        turnState.setActiveCanvasRun(runId, canvasId);
                    }
                }
            }
            // 用户已在画布编辑模式：直接在当前画布上作业，不要求先建任务、也不另建画布。
            // turnId 为空时不做自动接管：run 记录以 turnId 去重，缺 turnId 会每调一次建一条 run。
            if ((runId == null || canvasId == null) && turnId != null && !turnId.isBlank()
                    && context != null && context.conversation() != null && context.request() != null) {
                String editCanvasId = contextCanvasId(context.request().getCanvasContext());
                if (editCanvasId != null) {
                    AgentCanvasRunService.CanvasRunView adopted = runs.adoptCurrentCanvas(
                            context.conversation(), turnId, editCanvasId);
                    if (adopted != null) {
                        runId = adopted.runId();
                        canvasId = adopted.canvasId();
                        if (turnState != null) {
                            turnState.setActiveCanvasRun(runId, canvasId);
                        }
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
                parsed = new LinkedHashMap<>(runs.recordGraphResult(runId, operationKey, parsed));
            }
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

    /** 画布编辑模式下随本轮消息带上的画布 id；非画布模式返回 null。 */
    private static String contextCanvasId(com.moon.moonagent.ai.agent.CanvasSessionContext canvasContext) {
        if (canvasContext == null || !canvasContext.isCanvasEditMode()) return null;
        String canvasId = canvasContext.canvasId();
        return canvasId == null || canvasId.isBlank() ? null : canvasId.trim();
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
