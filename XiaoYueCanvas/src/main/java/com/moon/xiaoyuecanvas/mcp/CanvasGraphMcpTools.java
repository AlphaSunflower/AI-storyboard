package com.moon.xiaoyuecanvas.mcp;

import com.moon.xiaoyuecanvas.service.CanvasGraphCommandContext;
import com.moon.xiaoyuecanvas.service.CanvasGraphCommandException;
import com.moon.xiaoyuecanvas.service.CanvasGraphCommandService;
import com.storyboard.common.BusinessException;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side CanvasGraphV2 MCP tools.
 *
 * <p>MoonAgent injects userId/projectId/canvasId/runId/operationKey. The browser
 * Bridge is not involved in this execution path.
 */
@Component
public class CanvasGraphMcpTools {

    private final CanvasGraphCommandService commands;

    public CanvasGraphMcpTools(CanvasGraphCommandService commands) {
        this.commands = commands;
    }

    @Tool(description = "Get the current canvas context, revision and graph counts.")
    @McpTool(name = "canvas_graph_get_context", description = "Get canvas context.")
    public Map<String, Object> canvasGraphGetContext(
            @ToolParam(description = "Internal user id injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal user id", required = false) String userId,
            @ToolParam(description = "Internal project id injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal project id", required = false) String projectId,
            @ToolParam(description = "Internal canvas id injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal canvas id", required = false) String canvasId,
            @ToolParam(description = "Internal canvas run id injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal canvas run id", required = false) String runId,
            @ToolParam(description = "Internal idempotency key injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal idempotency key", required = false) String operationKey) {
        return run(() -> commands.getContext(ctx(userId, projectId, canvasId, runId, operationKey)));
    }

    @Tool(description = "Get the full CanvasGraphV2 state or a paged node view.")
    @McpTool(name = "canvas_graph_get_state", description = "Get canvas graph state.")
    public Map<String, Object> canvasGraphGetState(
            @ToolParam(description = "Internal user id injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal user id", required = false) String userId,
            @ToolParam(description = "Internal project id injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal project id", required = false) String projectId,
            @ToolParam(description = "Internal canvas id injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal canvas id", required = false) String canvasId,
            @ToolParam(description = "Internal canvas run id injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal canvas run id", required = false) String runId,
            @ToolParam(description = "Internal idempotency key injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal idempotency key", required = false) String operationKey,
            @ToolParam(description = "Optional node limit", required = false)
            @McpToolParam(description = "Optional node limit", required = false) Integer limit,
            @ToolParam(description = "Optional node offset", required = false)
            @McpToolParam(description = "Optional node offset", required = false) Integer offset,
            @ToolParam(description = "Whether to include edges", required = false)
            @McpToolParam(description = "Whether to include edges", required = false) Boolean includeEdges) {
        return run(() -> commands.getState(ctx(userId, projectId, canvasId, runId, operationKey),
                limit, offset, includeEdges));
    }

    @Tool(description = "List canvas nodes. nodeIds is optional.")
    @McpTool(name = "canvas_graph_list_nodes", description = "List canvas nodes.")
    public Map<String, Object> canvasGraphListNodes(
            @ToolParam(description = "Internal user id injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal user id", required = false) String userId,
            @ToolParam(description = "Internal project id injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal project id", required = false) String projectId,
            @ToolParam(description = "Internal canvas id injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal canvas id", required = false) String canvasId,
            @ToolParam(description = "Internal canvas run id injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal canvas run id", required = false) String runId,
            @ToolParam(description = "Internal idempotency key injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal idempotency key", required = false) String operationKey,
            @ToolParam(description = "Optional node ids", required = false)
            @McpToolParam(description = "Optional node ids", required = false) List<String> nodeIds) {
        return run(() -> commands.listNodes(ctx(userId, projectId, canvasId, runId, operationKey), nodeIds));
    }

    @Tool(description = "Get one canvas node by id.")
    @McpTool(name = "canvas_graph_get_node", description = "Get one canvas node.")
    public Map<String, Object> canvasGraphGetNode(
            @ToolParam(description = "Internal user id injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal user id", required = false) String userId,
            @ToolParam(description = "Internal project id injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal project id", required = false) String projectId,
            @ToolParam(description = "Internal canvas id injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal canvas id", required = false) String canvasId,
            @ToolParam(description = "Internal canvas run id injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal canvas run id", required = false) String runId,
            @ToolParam(description = "Internal idempotency key injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal idempotency key", required = false) String operationKey,
            @ToolParam(description = "Node id") @McpToolParam(description = "Node id") String nodeId) {
        return run(() -> commands.getNode(ctx(userId, projectId, canvasId, runId, operationKey), nodeId));
    }

    @Tool(description = "List canvas edges. edgeIds is optional.")
    @McpTool(name = "canvas_graph_list_edges", description = "List canvas edges.")
    public Map<String, Object> canvasGraphListEdges(
            @ToolParam(description = "Internal user id injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal user id", required = false) String userId,
            @ToolParam(description = "Internal project id injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal project id", required = false) String projectId,
            @ToolParam(description = "Internal canvas id injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal canvas id", required = false) String canvasId,
            @ToolParam(description = "Internal canvas run id injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal canvas run id", required = false) String runId,
            @ToolParam(description = "Internal idempotency key injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal idempotency key", required = false) String operationKey,
            @ToolParam(description = "Optional edge ids", required = false)
            @McpToolParam(description = "Optional edge ids", required = false) List<String> edgeIds) {
        return run(() -> commands.listEdges(ctx(userId, projectId, canvasId, runId, operationKey), edgeIds));
    }

    @Tool(description = "List server-side canvas node types, ports and compatibility rules.")
    @McpTool(name = "canvas_graph_list_node_types", description = "List canvas node types.")
    public Map<String, Object> canvasGraphListNodeTypes(
            @ToolParam(description = "Internal user id injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal user id", required = false) String userId,
            @ToolParam(description = "Internal project id injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal project id", required = false) String projectId,
            @ToolParam(description = "Internal canvas id injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal canvas id", required = false) String canvasId,
            @ToolParam(description = "Internal canvas run id injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal canvas run id", required = false) String runId,
            @ToolParam(description = "Internal idempotency key injected by MoonAgent", required = false)
            @McpToolParam(description = "Internal idempotency key", required = false) String operationKey) {
        return run(() -> commands.listNodeTypes(ctx(userId, projectId, canvasId, runId, operationKey)));
    }

    @Tool(description = "Create a CanvasGraphV2 node.")
    @McpTool(name = "canvas_graph_create_node", description = "Create a canvas node.")
    public Map<String, Object> canvasGraphCreateNode(
            @ToolParam(description = "Internal user id injected by MoonAgent")
            @McpToolParam(description = "Internal user id") String userId,
            @ToolParam(description = "Internal project id injected by MoonAgent")
            @McpToolParam(description = "Internal project id") String projectId,
            @ToolParam(description = "Internal canvas id injected by MoonAgent")
            @McpToolParam(description = "Internal canvas id") String canvasId,
            @ToolParam(description = "Internal canvas run id injected by MoonAgent")
            @McpToolParam(description = "Internal canvas run id") String runId,
            @ToolParam(description = "Internal idempotency key injected by MoonAgent")
            @McpToolParam(description = "Internal idempotency key") String operationKey,
            @ToolParam(description = "Node object: id/type/position/width/height/data")
            @McpToolParam(description = "Node object") Map<String, Object> node) {
        return run(() -> commands.createNode(ctx(userId, projectId, canvasId, runId, operationKey), node));
    }

    @Tool(description = "Create multiple CanvasGraphV2 nodes in one CAS transaction.")
    @McpTool(name = "canvas_graph_create_nodes", description = "Create canvas nodes.")
    public Map<String, Object> canvasGraphCreateNodes(
            @ToolParam(description = "Internal user id injected by MoonAgent")
            @McpToolParam(description = "Internal user id") String userId,
            @ToolParam(description = "Internal project id injected by MoonAgent")
            @McpToolParam(description = "Internal project id") String projectId,
            @ToolParam(description = "Internal canvas id injected by MoonAgent")
            @McpToolParam(description = "Internal canvas id") String canvasId,
            @ToolParam(description = "Internal canvas run id injected by MoonAgent")
            @McpToolParam(description = "Internal canvas run id") String runId,
            @ToolParam(description = "Internal idempotency key injected by MoonAgent")
            @McpToolParam(description = "Internal idempotency key") String operationKey,
            @ToolParam(description = "Node objects, maximum 100")
            @McpToolParam(description = "Node objects") List<Map<String, Object>> items) {
        return run(() -> commands.createNodes(ctx(userId, projectId, canvasId, runId, operationKey), items));
    }

    @Tool(description = "Update one canvas node's data fields.")
    @McpTool(name = "canvas_graph_update_node", description = "Update a canvas node.")
    public Map<String, Object> canvasGraphUpdateNode(
            @ToolParam(description = "Internal user id injected by MoonAgent")
            @McpToolParam(description = "Internal user id") String userId,
            @ToolParam(description = "Internal project id injected by MoonAgent")
            @McpToolParam(description = "Internal project id") String projectId,
            @ToolParam(description = "Internal canvas id injected by MoonAgent")
            @McpToolParam(description = "Internal canvas id") String canvasId,
            @ToolParam(description = "Internal canvas run id injected by MoonAgent")
            @McpToolParam(description = "Internal canvas run id") String runId,
            @ToolParam(description = "Internal idempotency key injected by MoonAgent")
            @McpToolParam(description = "Internal idempotency key") String operationKey,
            @ToolParam(description = "Node id") @McpToolParam(description = "Node id") String nodeId,
            @ToolParam(description = "Data patch") @McpToolParam(description = "Data patch")
            Map<String, Object> patch) {
        return run(() -> commands.updateNode(ctx(userId, projectId, canvasId, runId, operationKey), nodeId, patch));
    }

    @Tool(description = "Create a validated canvas edge.")
    @McpTool(name = "canvas_graph_create_edge", description = "Create a canvas edge.")
    public Map<String, Object> canvasGraphCreateEdge(
            @ToolParam(description = "Internal user id injected by MoonAgent")
            @McpToolParam(description = "Internal user id") String userId,
            @ToolParam(description = "Internal project id injected by MoonAgent")
            @McpToolParam(description = "Internal project id") String projectId,
            @ToolParam(description = "Internal canvas id injected by MoonAgent")
            @McpToolParam(description = "Internal canvas id") String canvasId,
            @ToolParam(description = "Internal canvas run id injected by MoonAgent")
            @McpToolParam(description = "Internal canvas run id") String runId,
            @ToolParam(description = "Internal idempotency key injected by MoonAgent")
            @McpToolParam(description = "Internal idempotency key") String operationKey,
            @ToolParam(description = "Edge object: id/source/sourceHandle/target/targetHandle/data")
            @McpToolParam(description = "Edge object") Map<String, Object> edge) {
        return run(() -> commands.createEdge(ctx(userId, projectId, canvasId, runId, operationKey), edge));
    }

    @Tool(description = "Import durable media and create a canvas image/video node.")
    @McpTool(name = "canvas_graph_import_media", description = "Import media into a canvas.")
    public Map<String, Object> canvasGraphImportMedia(
            @ToolParam(description = "Internal user id injected by MoonAgent")
            @McpToolParam(description = "Internal user id") String userId,
            @ToolParam(description = "Internal project id injected by MoonAgent")
            @McpToolParam(description = "Internal project id") String projectId,
            @ToolParam(description = "Internal canvas id injected by MoonAgent")
            @McpToolParam(description = "Internal canvas id") String canvasId,
            @ToolParam(description = "Internal canvas run id injected by MoonAgent")
            @McpToolParam(description = "Internal canvas run id") String runId,
            @ToolParam(description = "Internal idempotency key injected by MoonAgent")
            @McpToolParam(description = "Internal idempotency key") String operationKey,
            @ToolParam(description = "Media import object: kind/url/assetId/mediaId/libraryAssetId/name/mimeType/label/position")
            @McpToolParam(description = "Media import object") Map<String, Object> media) {
        return run(() -> commands.importMedia(ctx(userId, projectId, canvasId, runId, operationKey), media));
    }

    @Tool(description = "Import multiple durable media items and create all nodes in one graph revision.")
    @McpTool(name = "canvas_graph_import_media_batch", description = "Batch import media into a canvas.")
    public Map<String, Object> canvasGraphImportMediaBatch(
            @ToolParam(description = "Internal user id injected by MoonAgent")
            @McpToolParam(description = "Internal user id") String userId,
            @ToolParam(description = "Internal project id injected by MoonAgent")
            @McpToolParam(description = "Internal project id") String projectId,
            @ToolParam(description = "Internal canvas id injected by MoonAgent")
            @McpToolParam(description = "Internal canvas id") String canvasId,
            @ToolParam(description = "Internal canvas run id injected by MoonAgent")
            @McpToolParam(description = "Internal canvas run id") String runId,
            @ToolParam(description = "Internal idempotency key injected by MoonAgent")
            @McpToolParam(description = "Internal idempotency key") String operationKey,
            @ToolParam(description = "Media import objects")
            @McpToolParam(description = "Media import objects") List<Map<String, Object>> mediaItems) {
        return run(() -> commands.importMediaBatch(
                ctx(userId, projectId, canvasId, runId, operationKey), mediaItems));
    }

    @Tool(description = "Create ordered image/video result slots for a producer node.")
    @McpTool(name = "canvas_graph_create_result_slots", description = "Create canvas result slots.")
    public Map<String, Object> canvasGraphCreateResultSlots(
            @ToolParam(description = "Internal user id injected by MoonAgent")
            @McpToolParam(description = "Internal user id") String userId,
            @ToolParam(description = "Internal project id injected by MoonAgent")
            @McpToolParam(description = "Internal project id") String projectId,
            @ToolParam(description = "Internal canvas id injected by MoonAgent")
            @McpToolParam(description = "Internal canvas id") String canvasId,
            @ToolParam(description = "Internal canvas run id injected by MoonAgent")
            @McpToolParam(description = "Internal canvas run id") String runId,
            @ToolParam(description = "Internal idempotency key injected by MoonAgent")
            @McpToolParam(description = "Internal idempotency key") String operationKey,
            @ToolParam(description = "Result slot arguments: producerNodeId/kind/count/position/gapY")
            @McpToolParam(description = "Result slot arguments") Map<String, Object> args) {
        return run(() -> commands.createResultSlots(ctx(userId, projectId, canvasId, runId, operationKey), args));
    }

    @Tool(description = "Mark existing empty image/video nodes as result slots.")
    @McpTool(name = "canvas_graph_mark_result_slots", description = "Mark canvas result slots.")
    public Map<String, Object> canvasGraphMarkResultSlots(
            @ToolParam(description = "Internal user id injected by MoonAgent")
            @McpToolParam(description = "Internal user id") String userId,
            @ToolParam(description = "Internal project id injected by MoonAgent")
            @McpToolParam(description = "Internal project id") String projectId,
            @ToolParam(description = "Internal canvas id injected by MoonAgent")
            @McpToolParam(description = "Internal canvas id") String canvasId,
            @ToolParam(description = "Internal canvas run id injected by MoonAgent")
            @McpToolParam(description = "Internal canvas run id") String runId,
            @ToolParam(description = "Internal idempotency key injected by MoonAgent")
            @McpToolParam(description = "Internal idempotency key") String operationKey,
            @ToolParam(description = "Result slot arguments: producerNodeId/nodeIds/startOrder")
            @McpToolParam(description = "Result slot arguments") Map<String, Object> args) {
        return run(() -> commands.markResultSlots(ctx(userId, projectId, canvasId, runId, operationKey), args));
    }

    @Tool(description = "Lay out canvas nodes using grid/horizontal/vertical/dag mode.")
    @McpTool(name = "canvas_graph_layout_graph", description = "Lay out a canvas graph.")
    public Map<String, Object> canvasGraphLayoutGraph(
            @ToolParam(description = "Internal user id injected by MoonAgent")
            @McpToolParam(description = "Internal user id") String userId,
            @ToolParam(description = "Internal project id injected by MoonAgent")
            @McpToolParam(description = "Internal project id") String projectId,
            @ToolParam(description = "Internal canvas id injected by MoonAgent")
            @McpToolParam(description = "Internal canvas id") String canvasId,
            @ToolParam(description = "Internal canvas run id injected by MoonAgent")
            @McpToolParam(description = "Internal canvas run id") String runId,
            @ToolParam(description = "Internal idempotency key injected by MoonAgent")
            @McpToolParam(description = "Internal idempotency key") String operationKey,
            @ToolParam(description = "Layout arguments: nodeIds/mode/gapX/gapY/columns/anchor")
            @McpToolParam(description = "Layout arguments") Map<String, Object> args) {
        return run(() -> commands.layoutGraph(ctx(userId, projectId, canvasId, runId, operationKey), args));
    }

    private static CanvasGraphCommandContext ctx(
            String userId,
            String projectId,
            String canvasId,
            String runId,
            String operationKey
    ) {
        return new CanvasGraphCommandContext(userId, projectId, canvasId, runId, operationKey);
    }

    private static Map<String, Object> run(java.util.function.Supplier<Map<String, Object>> action) {
        try {
            return action.get();
        } catch (CanvasGraphCommandException error) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("code", error.code());
            detail.put("message", error.getMessage());
            if (!error.details().isEmpty()) detail.put("details", error.details());
            out.put("error", detail);
            return out;
        } catch (BusinessException error) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("error", Map.of(
                    "code", "BUSINESS_" + error.getCode(),
                    "message", error.getMessage() == null ? "画布命令失败" : error.getMessage()));
            return out;
        } catch (RuntimeException error) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("error", Map.of(
                    "code", "CANVAS_COMMAND_FAILED",
                    "message", error.getMessage() == null ? "画布命令失败" : error.getMessage()));
            return out;
        }
    }
}
