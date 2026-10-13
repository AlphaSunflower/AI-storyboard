package com.moon.xiaoyuecanvas.mcp;

import com.moon.xiaoyuecanvas.bridge.CanvasBrowserMailbox;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 浏览器执行类画布工具（原 canvas-bridge 进程提供的 4 个工具）。
 *
 * <p>这些命令必须在浏览器页面内执行：命令经 {@link CanvasBrowserMailbox} 排队，
 * 浏览器轮询领取后回传结果，因此调用会阻塞到浏览器响应或超时（上限 11 分钟）。
 *
 * <p>MoonAgent 注入 userId/projectId/canvasId/runId/operationKey；工具名与返回形状
 * 与并入前完全一致，模型可见的工具集、skill allowed-tools 与权限表都不需要改。
 */
@Component
public class CanvasExecuteMcpTools {

    private final CanvasBrowserMailbox mailbox;

    public CanvasExecuteMcpTools(CanvasBrowserMailbox mailbox) {
        this.mailbox = mailbox;
    }

    @Tool(description = "提交单个节点的动作并立即返回 jobId。")
    @McpTool(name = "canvas_execute_node", description = "提交单个节点的动作并立即返回 jobId。")
    public Map<String, Object> canvasExecuteNode(
            @ToolParam(description = "Internal user id injected by MoonAgent")
            @McpToolParam(description = "Internal user id", required = false) String userId,
            @ToolParam(description = "Internal project id injected by MoonAgent")
            @McpToolParam(description = "Internal project id", required = false) String projectId,
            @ToolParam(description = "Internal canvas id injected by MoonAgent")
            @McpToolParam(description = "Internal canvas id", required = false) String canvasId,
            @ToolParam(description = "Internal canvas run id injected by MoonAgent")
            @McpToolParam(description = "Internal canvas run id", required = false) String runId,
            @ToolParam(description = "Internal idempotency key injected by MoonAgent")
            @McpToolParam(description = "Internal idempotency key", required = false) String operationKey,
            @ToolParam(description = "Node id") @McpToolParam(description = "Node id") String nodeId,
            @ToolParam(description = "Optional action id", required = false)
            @McpToolParam(description = "Optional action id", required = false) String actionId,
            @ToolParam(description = "Preview only", required = false)
            @McpToolParam(description = "Preview only", required = false) Boolean dryRun) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("nodeId", nodeId);
        if (actionId != null && !actionId.isBlank()) params.put("actionId", actionId);
        return dispatch(userId, projectId, canvasId, "canvas.node.execute", params, dryRun);
    }

    @Tool(description = "按依赖顺序执行画布节点，失败节点的下游阻断，独立分支继续。")
    @McpTool(name = "canvas_execute_graph",
            description = "按依赖顺序执行画布节点，失败节点的下游阻断，独立分支继续。")
    public Map<String, Object> canvasExecuteGraph(
            @ToolParam(description = "Internal user id injected by MoonAgent")
            @McpToolParam(description = "Internal user id", required = false) String userId,
            @ToolParam(description = "Internal project id injected by MoonAgent")
            @McpToolParam(description = "Internal project id", required = false) String projectId,
            @ToolParam(description = "Internal canvas id injected by MoonAgent")
            @McpToolParam(description = "Internal canvas id", required = false) String canvasId,
            @ToolParam(description = "Internal canvas run id injected by MoonAgent")
            @McpToolParam(description = "Internal canvas run id", required = false) String runId,
            @ToolParam(description = "Internal idempotency key injected by MoonAgent")
            @McpToolParam(description = "Internal idempotency key", required = false) String operationKey,
            @ToolParam(description = "Optional node ids to execute", required = false)
            @McpToolParam(description = "Optional node ids to execute", required = false) List<String> nodeIds,
            @ToolParam(description = "Optional action id per node", required = false)
            @McpToolParam(description = "Optional action id per node", required = false)
            Map<String, Object> actionByNode,
            @ToolParam(description = "Optional max parallelism 1..2", required = false)
            @McpToolParam(description = "Optional max parallelism 1..2", required = false) Integer maxParallel,
            @ToolParam(description = "Preview only", required = false)
            @McpToolParam(description = "Preview only", required = false) Boolean dryRun) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (nodeIds != null && !nodeIds.isEmpty()) params.put("nodeIds", nodeIds);
        if (actionByNode != null && !actionByNode.isEmpty()) params.put("actionByNode", actionByNode);
        if (maxParallel != null) params.put("maxParallel", maxParallel);
        return dispatch(userId, projectId, canvasId, "canvas.graph.execute", params, dryRun);
    }

    @Tool(description = "读取 DAG 执行批次和各节点状态。")
    @McpTool(name = "canvas_get_execution", description = "读取 DAG 执行批次和各节点状态。")
    public Map<String, Object> canvasGetExecution(
            @ToolParam(description = "Internal user id injected by MoonAgent")
            @McpToolParam(description = "Internal user id", required = false) String userId,
            @ToolParam(description = "Internal project id injected by MoonAgent")
            @McpToolParam(description = "Internal project id", required = false) String projectId,
            @ToolParam(description = "Internal canvas id injected by MoonAgent")
            @McpToolParam(description = "Internal canvas id", required = false) String canvasId,
            @ToolParam(description = "Internal canvas run id injected by MoonAgent")
            @McpToolParam(description = "Internal canvas run id", required = false) String runId,
            @ToolParam(description = "Internal idempotency key injected by MoonAgent")
            @McpToolParam(description = "Internal idempotency key", required = false) String operationKey,
            @ToolParam(description = "Execution id") @McpToolParam(description = "Execution id")
            String executionId) {
        return dispatch(userId, projectId, canvasId, "canvas.execution.get",
                Map.of("executionId", executionId), null);
    }

    @Tool(description = "取消 DAG 批跑及其仍在运行的任务。")
    @McpTool(name = "canvas_cancel_execution", description = "取消 DAG 批跑及其仍在运行的任务。")
    public Map<String, Object> canvasCancelExecution(
            @ToolParam(description = "Internal user id injected by MoonAgent")
            @McpToolParam(description = "Internal user id", required = false) String userId,
            @ToolParam(description = "Internal project id injected by MoonAgent")
            @McpToolParam(description = "Internal project id", required = false) String projectId,
            @ToolParam(description = "Internal canvas id injected by MoonAgent")
            @McpToolParam(description = "Internal canvas id", required = false) String canvasId,
            @ToolParam(description = "Internal canvas run id injected by MoonAgent")
            @McpToolParam(description = "Internal canvas run id", required = false) String runId,
            @ToolParam(description = "Internal idempotency key injected by MoonAgent")
            @McpToolParam(description = "Internal idempotency key", required = false) String operationKey,
            @ToolParam(description = "Execution id") @McpToolParam(description = "Execution id")
            String executionId) {
        return dispatch(userId, projectId, canvasId, "canvas.execution.cancel",
                Map.of("executionId", executionId), null);
    }

    private Map<String, Object> dispatch(String userId,
                                         String projectId,
                                         String canvasId,
                                         String method,
                                         Map<String, Object> params,
                                         Boolean dryRun) {
        return toToolPayload(mailbox.command(
                userId, projectId, canvasId, method, params, Boolean.TRUE.equals(dryRun), null));
    }

    /**
     * 把信箱响应折成与 canvas_graph_* 一致的载荷：成功 {@code {ok:true, ...result}}，
     * 失败 {@code {ok:false, error:{code,message,details?}}}。
     */
    static Map<String, Object> toToolPayload(Map<String, Object> response) {
        if (response == null) {
            return failure("CANVAS_COMMAND_FAILED", "画布命令失败", null);
        }
        if (!Boolean.TRUE.equals(response.get("ok"))) {
            Object error = response.get("error");
            if (error instanceof Map<?, ?> errorMap) {
                return failure(
                        stringValue(errorMap.get("code"), "CANVAS_COMMAND_FAILED"),
                        stringValue(errorMap.get("message"), "画布命令失败"),
                        errorMap.get("details"));
            }
            return failure("CANVAS_COMMAND_FAILED", "画布命令失败", null);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Object result = response.get("result");
        if (result instanceof Map<?, ?> resultMap) {
            for (Map.Entry<?, ?> entry : resultMap.entrySet()) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        } else if (result != null) {
            out.put("result", result);
        }
        out.put("ok", true);
        copyIfPresent(response, out, "status");
        copyIfPresent(response, out, "revision");
        copyIfPresent(response, out, "transactionId");
        return out;
    }

    private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) target.put(key, value);
    }

    private static Map<String, Object> failure(String code, String message, Object details) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        if (details != null) error.put("details", details);
        out.put("error", error);
        return out;
    }

    private static String stringValue(Object value, String fallback) {
        return value instanceof String string && !string.isBlank() ? string : fallback;
    }
}
