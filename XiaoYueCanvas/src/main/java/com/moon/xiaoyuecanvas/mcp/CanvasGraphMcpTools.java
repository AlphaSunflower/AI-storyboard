package com.moon.xiaoyuecanvas.mcp;

import com.moon.xiaoyuecanvas.mcp.CanvasGraphToolInputs.EdgeSpec;
import com.moon.xiaoyuecanvas.mcp.CanvasGraphToolInputs.LayoutSpec;
import com.moon.xiaoyuecanvas.mcp.CanvasGraphToolInputs.MarkResultSlotArgs;
import com.moon.xiaoyuecanvas.mcp.CanvasGraphToolInputs.MediaSpec;
import com.moon.xiaoyuecanvas.mcp.CanvasGraphToolInputs.NodePatch;
import com.moon.xiaoyuecanvas.mcp.CanvasGraphToolInputs.NodeSpec;
import com.moon.xiaoyuecanvas.mcp.CanvasGraphToolInputs.PlanSpec;
import com.moon.xiaoyuecanvas.mcp.CanvasGraphToolInputs.ResultSlotArgs;
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
 *
 * <p>业务入参一律用类型化 record（见 {@link CanvasGraphToolInputs}），让模型拿到字段级 JSON Schema，
 * 而不是靠 description 里的一句"Edge object: ..."去猜键名。注入型参数必须保持
 * {@code required = false}（MoonAgent 在模型调用之后注入；标成必填会让 AgentScope 先做 schema
 * 校验并失败）。
 */
@Component
public class CanvasGraphMcpTools {

    private static final String INJECTED_USER = "内部参数：由 MoonAgent 自动注入，模型不要传";
    private static final String INJECTED_PROJECT = "内部参数：由 MoonAgent 自动注入，模型不要传";
    private static final String INJECTED_CANVAS = "内部参数：由 MoonAgent 自动注入，模型不要传";
    private static final String INJECTED_RUN = "内部参数：由 MoonAgent 自动注入，模型不要传";
    private static final String INJECTED_OPERATION = "内部幂等键：由 MoonAgent 自动注入，模型不要传";

    private static final String DETAIL_WRITE =
            "是否额外回显完整对象（可选，默认 false）：patch 里已经包含本次新增/修改的完整节点与连线；"
                    + "传 true 会再额外返回 node/nodes/edge/edges 等整份对象，仅排错时用";
    private static final String DETAIL_READ =
            "是否返回完整对象（可选，默认 false）：默认是紧凑视图（节点只含 id/type/label/mode/status/position/"
                    + "width/height/assetId，连线只含端点与 data.portOrder/resultSlot）；传 true 返回完整 data（含 prompt、assetUrl 等全部字段）";

    private static final String PATCH_FIELD =
            "；返回里的 patch={nodes,edges,removedNodeIds,removedEdgeIds} 是本次变更明细，前端据此增量应用画布";

    private final CanvasGraphCommandService commands;

    public CanvasGraphMcpTools(CanvasGraphCommandService commands) {
        this.commands = commands;
    }

    @Tool(description = "Get the current canvas context, revision and graph counts.")
    @McpTool(name = "canvas_graph_get_context",
            description = "读取当前画布上下文：canvasId、projectId、标题、revision、节点数与连线数。"
                    + "只读工具，不改画布；组链前后用它确认 revision 是否按预期前进。")
    public Map<String, Object> canvasGraphGetContext(
            @ToolParam(description = INJECTED_USER, required = false)
            @McpToolParam(description = INJECTED_USER, required = false) String userId,
            @ToolParam(description = INJECTED_PROJECT, required = false)
            @McpToolParam(description = INJECTED_PROJECT, required = false) String projectId,
            @ToolParam(description = INJECTED_CANVAS, required = false)
            @McpToolParam(description = INJECTED_CANVAS, required = false) String canvasId,
            @ToolParam(description = INJECTED_RUN, required = false)
            @McpToolParam(description = INJECTED_RUN, required = false) String runId,
            @ToolParam(description = INJECTED_OPERATION, required = false)
            @McpToolParam(description = INJECTED_OPERATION, required = false) String operationKey) {
        return run(() -> commands.getContext(ctx(userId, projectId, canvasId, runId, operationKey)));
    }

    @Tool(description = "Get the full CanvasGraphV2 state or a paged node view.")
    @McpTool(name = "canvas_graph_get_state",
            description = "读取画布图状态（节点 + 连线），默认紧凑视图。limit/offset 只对节点分页（limit 省略时不分页），"
                    + "includeEdges=false 时不返回连线。nodeCount/edgeCount 始终是整张画布的总数。" + DETAIL_READ)
    public Map<String, Object> canvasGraphGetState(
            @ToolParam(description = INJECTED_USER, required = false)
            @McpToolParam(description = INJECTED_USER, required = false) String userId,
            @ToolParam(description = INJECTED_PROJECT, required = false)
            @McpToolParam(description = INJECTED_PROJECT, required = false) String projectId,
            @ToolParam(description = INJECTED_CANVAS, required = false)
            @McpToolParam(description = INJECTED_CANVAS, required = false) String canvasId,
            @ToolParam(description = INJECTED_RUN, required = false)
            @McpToolParam(description = INJECTED_RUN, required = false) String runId,
            @ToolParam(description = INJECTED_OPERATION, required = false)
            @McpToolParam(description = INJECTED_OPERATION, required = false) String operationKey,
            @ToolParam(description = "节点条数上限（可选）：省略或 <=0 = 不分页，返回全部节点", required = false)
            @McpToolParam(description = "节点条数上限（可选）：省略或 <=0 = 不分页，返回全部节点", required = false)
            Integer limit,
            @ToolParam(description = "节点起始偏移（可选，默认 0）", required = false)
            @McpToolParam(description = "节点起始偏移（可选，默认 0）", required = false) Integer offset,
            @ToolParam(description = "是否返回连线（可选，默认 true）：false 时 graph.edges 为空数组", required = false)
            @McpToolParam(description = "是否返回连线（可选，默认 true）：false 时 graph.edges 为空数组", required = false)
            Boolean includeEdges,
            @ToolParam(description = DETAIL_READ, required = false)
            @McpToolParam(description = DETAIL_READ, required = false) Boolean detail) {
        return run(() -> commands.getState(ctx(userId, projectId, canvasId, runId, operationKey),
                limit, offset, includeEdges, Boolean.TRUE.equals(detail)));
    }

    @Tool(description = "List canvas nodes. nodeIds is optional.")
    @McpTool(name = "canvas_graph_list_nodes",
            description = "列出画布节点（默认紧凑视图，每次最多 100 条，按 offset 翻页）。返回 total 便于判断还有多少；"
                    + "需要某个节点的完整 data（prompt/assetUrl）时用 canvas_graph_get_node。" + DETAIL_READ)
    public Map<String, Object> canvasGraphListNodes(
            @ToolParam(description = INJECTED_USER, required = false)
            @McpToolParam(description = INJECTED_USER, required = false) String userId,
            @ToolParam(description = INJECTED_PROJECT, required = false)
            @McpToolParam(description = INJECTED_PROJECT, required = false) String projectId,
            @ToolParam(description = INJECTED_CANVAS, required = false)
            @McpToolParam(description = INJECTED_CANVAS, required = false) String canvasId,
            @ToolParam(description = INJECTED_RUN, required = false)
            @McpToolParam(description = INJECTED_RUN, required = false) String runId,
            @ToolParam(description = INJECTED_OPERATION, required = false)
            @McpToolParam(description = INJECTED_OPERATION, required = false) String operationKey,
            @ToolParam(description = "只列这些节点 id（可选）：省略时列全部节点", required = false)
            @McpToolParam(description = "只列这些节点 id（可选）：省略时列全部节点", required = false)
            List<String> nodeIds,
            @ToolParam(description = "本页条数（可选，默认 100）", required = false)
            @McpToolParam(description = "本页条数（可选，默认 100）", required = false) Integer limit,
            @ToolParam(description = "起始偏移（可选，默认 0）", required = false)
            @McpToolParam(description = "起始偏移（可选，默认 0）", required = false) Integer offset,
            @ToolParam(description = DETAIL_READ, required = false)
            @McpToolParam(description = DETAIL_READ, required = false) Boolean detail) {
        return run(() -> commands.listNodes(ctx(userId, projectId, canvasId, runId, operationKey),
                nodeIds, limit, offset, Boolean.TRUE.equals(detail)));
    }

    @Tool(description = "Get one canvas node by id.")
    @McpTool(name = "canvas_graph_get_node",
            description = "按 id 读取单个节点的完整对象（含全部 data）。连线前用它确认端口、data.mode 与已有素材。")
    public Map<String, Object> canvasGraphGetNode(
            @ToolParam(description = INJECTED_USER, required = false)
            @McpToolParam(description = INJECTED_USER, required = false) String userId,
            @ToolParam(description = INJECTED_PROJECT, required = false)
            @McpToolParam(description = INJECTED_PROJECT, required = false) String projectId,
            @ToolParam(description = INJECTED_CANVAS, required = false)
            @McpToolParam(description = INJECTED_CANVAS, required = false) String canvasId,
            @ToolParam(description = INJECTED_RUN, required = false)
            @McpToolParam(description = INJECTED_RUN, required = false) String runId,
            @ToolParam(description = INJECTED_OPERATION, required = false)
            @McpToolParam(description = INJECTED_OPERATION, required = false) String operationKey,
            @ToolParam(description = "节点 id（必填）", required = true)
            @McpToolParam(description = "节点 id（必填）", required = true) String nodeId) {
        return run(() -> commands.getNode(ctx(userId, projectId, canvasId, runId, operationKey), nodeId));
    }

    @Tool(description = "List canvas edges. edgeIds is optional.")
    @McpTool(name = "canvas_graph_list_edges",
            description = "列出画布连线（默认紧凑视图，每次最多 100 条，按 offset 翻页）。返回 total；"
                    + "检查链路完整性用这个而不是 get_state。" + DETAIL_READ)
    public Map<String, Object> canvasGraphListEdges(
            @ToolParam(description = INJECTED_USER, required = false)
            @McpToolParam(description = INJECTED_USER, required = false) String userId,
            @ToolParam(description = INJECTED_PROJECT, required = false)
            @McpToolParam(description = INJECTED_PROJECT, required = false) String projectId,
            @ToolParam(description = INJECTED_CANVAS, required = false)
            @McpToolParam(description = INJECTED_CANVAS, required = false) String canvasId,
            @ToolParam(description = INJECTED_RUN, required = false)
            @McpToolParam(description = INJECTED_RUN, required = false) String runId,
            @ToolParam(description = INJECTED_OPERATION, required = false)
            @McpToolParam(description = INJECTED_OPERATION, required = false) String operationKey,
            @ToolParam(description = "只列这些连线 id（可选）：省略时列全部连线", required = false)
            @McpToolParam(description = "只列这些连线 id（可选）：省略时列全部连线", required = false)
            List<String> edgeIds,
            @ToolParam(description = "本页条数（可选，默认 100）", required = false)
            @McpToolParam(description = "本页条数（可选，默认 100）", required = false) Integer limit,
            @ToolParam(description = "起始偏移（可选，默认 0）", required = false)
            @McpToolParam(description = "起始偏移（可选，默认 0）", required = false) Integer offset,
            @ToolParam(description = DETAIL_READ, required = false)
            @McpToolParam(description = DETAIL_READ, required = false) Boolean detail) {
        return run(() -> commands.listEdges(ctx(userId, projectId, canvasId, runId, operationKey),
                edgeIds, limit, offset, Boolean.TRUE.equals(detail)));
    }

    @Tool(description = "List server-side canvas node types, ports and compatibility rules.")
    @McpTool(name = "canvas_graph_list_node_types",
            description = "列出可创建的节点类型、默认尺寸、输入/输出端口与端口兼容规则（acceptsKinds、max、required、ordered）。"
                    + "建节点前先读它，能避免建出前端不认识或连不上手的节点。")
    public Map<String, Object> canvasGraphListNodeTypes(
            @ToolParam(description = INJECTED_USER, required = false)
            @McpToolParam(description = INJECTED_USER, required = false) String userId,
            @ToolParam(description = INJECTED_PROJECT, required = false)
            @McpToolParam(description = INJECTED_PROJECT, required = false) String projectId,
            @ToolParam(description = INJECTED_CANVAS, required = false)
            @McpToolParam(description = INJECTED_CANVAS, required = false) String canvasId,
            @ToolParam(description = INJECTED_RUN, required = false)
            @McpToolParam(description = INJECTED_RUN, required = false) String runId,
            @ToolParam(description = INJECTED_OPERATION, required = false)
            @McpToolParam(description = INJECTED_OPERATION, required = false) String operationKey) {
        return run(() -> commands.listNodeTypes(ctx(userId, projectId, canvasId, runId, operationKey)));
    }

    @Tool(description = "Create a CanvasGraphV2 node.")
    @McpTool(name = "canvas_graph_create_node",
            description = "新建 1 个画布节点（一次命令 = 一个 revision）。node.type 决定节点种类，"
                    + "node.label/mode/prompt 会写进节点 data；node.resultSlots 可在同一条命令里建结果槽。"
                    + "建多个节点请改用 canvas_graph_create_nodes（一次 revision 建多个）。" + PATCH_FIELD)
    public Map<String, Object> canvasGraphCreateNode(
            @ToolParam(description = INJECTED_USER, required = false)
            @McpToolParam(description = INJECTED_USER, required = false) String userId,
            @ToolParam(description = INJECTED_PROJECT, required = false)
            @McpToolParam(description = INJECTED_PROJECT, required = false) String projectId,
            @ToolParam(description = INJECTED_CANVAS, required = false)
            @McpToolParam(description = INJECTED_CANVAS, required = false) String canvasId,
            @ToolParam(description = INJECTED_RUN, required = false)
            @McpToolParam(description = INJECTED_RUN, required = false) String runId,
            @ToolParam(description = INJECTED_OPERATION, required = false)
            @McpToolParam(description = INJECTED_OPERATION, required = false) String operationKey,
            @ToolParam(description = "节点对象：type（必填）、id/ref/label/mode/prompt/status/position/width/height/refs/resultSlots/data", required = true)
            @McpToolParam(description = "节点对象：type（必填）、id/ref/label/mode/prompt/status/position/width/height/refs/resultSlots/data", required = true)
            NodeSpec node,
            @ToolParam(description = DETAIL_WRITE, required = false)
            @McpToolParam(description = DETAIL_WRITE, required = false) Boolean detail) {
        return run(() -> commands.createNode(ctx(userId, projectId, canvasId, runId, operationKey),
                node == null ? Map.of() : node.toMap(), Boolean.TRUE.equals(detail)));
    }

    @Tool(description = "Create multiple CanvasGraphV2 nodes in one CAS transaction.")
    @McpTool(name = "canvas_graph_create_nodes",
            description = "一次新建多个节点（1~100 个，同一 revision）。每个 item 可带 resultSlots 在本次命令内建槽。"
                    + "组链时优先用 canvas_graph_apply_plan（节点+连线+布局一次提交）。" + PATCH_FIELD)
    public Map<String, Object> canvasGraphCreateNodes(
            @ToolParam(description = INJECTED_USER, required = false)
            @McpToolParam(description = INJECTED_USER, required = false) String userId,
            @ToolParam(description = INJECTED_PROJECT, required = false)
            @McpToolParam(description = INJECTED_PROJECT, required = false) String projectId,
            @ToolParam(description = INJECTED_CANVAS, required = false)
            @McpToolParam(description = INJECTED_CANVAS, required = false) String canvasId,
            @ToolParam(description = INJECTED_RUN, required = false)
            @McpToolParam(description = INJECTED_RUN, required = false) String runId,
            @ToolParam(description = INJECTED_OPERATION, required = false)
            @McpToolParam(description = INJECTED_OPERATION, required = false) String operationKey,
            @ToolParam(description = "节点对象数组（必填，1~100 个）：每个元素的字段同 canvas_graph_create_node 的 node",
                    required = true)
            @McpToolParam(description = "节点对象数组（必填，1~100 个）：每个元素的字段同 canvas_graph_create_node 的 node",
                    required = true)
            List<NodeSpec> items,
            @ToolParam(description = DETAIL_WRITE, required = false)
            @McpToolParam(description = DETAIL_WRITE, required = false) Boolean detail) {
        return run(() -> commands.createNodes(ctx(userId, projectId, canvasId, runId, operationKey),
                CanvasGraphToolInputs.toNodeMaps(items), Boolean.TRUE.equals(detail)));
    }

    @Tool(description = "Update one canvas node's data fields.")
    @McpTool(name = "canvas_graph_update_node",
            description = "更新 1 个节点的 data 字段（改名/改提示词/改 mode/置 status 等）。两种写法等价且都支持："
                    + "① 直接传扁平字段（label、mode、prompt、status、assetId、assetUrl，推荐）；"
                    + "② 传 patch.data={...} 对象。**不要**写 patch.data.data；id/type/kind 不可改。"
                    + "没传的字段不会被置空。" + PATCH_FIELD)
    public Map<String, Object> canvasGraphUpdateNode(
            @ToolParam(description = INJECTED_USER, required = false)
            @McpToolParam(description = INJECTED_USER, required = false) String userId,
            @ToolParam(description = INJECTED_PROJECT, required = false)
            @McpToolParam(description = INJECTED_PROJECT, required = false) String projectId,
            @ToolParam(description = INJECTED_CANVAS, required = false)
            @McpToolParam(description = INJECTED_CANVAS, required = false) String canvasId,
            @ToolParam(description = INJECTED_RUN, required = false)
            @McpToolParam(description = INJECTED_RUN, required = false) String runId,
            @ToolParam(description = INJECTED_OPERATION, required = false)
            @McpToolParam(description = INJECTED_OPERATION, required = false) String operationKey,
            @ToolParam(description = "要更新的节点 id（必填）", required = true)
            @McpToolParam(description = "要更新的节点 id（必填）", required = true) String nodeId,
            @ToolParam(description = "要写入的 data 字段：扁平字段（label/mode/prompt/status/assetId/assetUrl）与 data={...} 等价",
                    required = true)
            @McpToolParam(description = "要写入的 data 字段：扁平字段（label/mode/prompt/status/assetId/assetUrl）与 data={...} 等价",
                    required = true)
            NodePatch patch,
            @ToolParam(description = DETAIL_WRITE, required = false)
            @McpToolParam(description = DETAIL_WRITE, required = false) Boolean detail) {
        return run(() -> commands.updateNode(ctx(userId, projectId, canvasId, runId, operationKey), nodeId,
                patch == null ? Map.of() : patch.toMap(), Boolean.TRUE.equals(detail)));
    }

    @Tool(description = "Create a validated canvas edge.")
    @McpTool(name = "canvas_graph_create_edge",
            description = "新建 1 条连线（服务端校验端口类型/端口上限/有序位次/成环）。source/target 必填；"
                    + "sourceHandle/targetHandle 省略时服务端按端口表自动选。连多条请改用 canvas_graph_create_edges。"
                    + PATCH_FIELD)
    public Map<String, Object> canvasGraphCreateEdge(
            @ToolParam(description = INJECTED_USER, required = false)
            @McpToolParam(description = INJECTED_USER, required = false) String userId,
            @ToolParam(description = INJECTED_PROJECT, required = false)
            @McpToolParam(description = INJECTED_PROJECT, required = false) String projectId,
            @ToolParam(description = INJECTED_CANVAS, required = false)
            @McpToolParam(description = INJECTED_CANVAS, required = false) String canvasId,
            @ToolParam(description = INJECTED_RUN, required = false)
            @McpToolParam(description = INJECTED_RUN, required = false) String runId,
            @ToolParam(description = INJECTED_OPERATION, required = false)
            @McpToolParam(description = INJECTED_OPERATION, required = false) String operationKey,
            @ToolParam(description = "连线对象：source/target（必填）、id/sourceHandle/targetHandle/portOrder/resultSlot/data",
                    required = true)
            @McpToolParam(description = "连线对象：source/target（必填）、id/sourceHandle/targetHandle/portOrder/resultSlot/data",
                    required = true)
            EdgeSpec edge,
            @ToolParam(description = DETAIL_WRITE, required = false)
            @McpToolParam(description = DETAIL_WRITE, required = false) Boolean detail) {
        return run(() -> commands.createEdge(ctx(userId, projectId, canvasId, runId, operationKey),
                edge == null ? Map.of() : edge.toMap(), Boolean.TRUE.equals(detail)));
    }

    @Tool(description = "Create multiple validated canvas edges in one CAS transaction.")
    @McpTool(name = "canvas_graph_create_edges",
            description = "一次新建多条连线（1~200 条，同一 revision）。**all-or-nothing**：只要有一条没通过校验，"
                    + "整批都不落库，并在 error.details.errors=[{index,code,message}] 里一次性列出**全部**失败项，"
                    + "便于一轮改完。已经存在的完全相同连线会被幂等忽略，不报错。" + PATCH_FIELD)
    public Map<String, Object> canvasGraphCreateEdges(
            @ToolParam(description = INJECTED_USER, required = false)
            @McpToolParam(description = INJECTED_USER, required = false) String userId,
            @ToolParam(description = INJECTED_PROJECT, required = false)
            @McpToolParam(description = INJECTED_PROJECT, required = false) String projectId,
            @ToolParam(description = INJECTED_CANVAS, required = false)
            @McpToolParam(description = INJECTED_CANVAS, required = false) String canvasId,
            @ToolParam(description = INJECTED_RUN, required = false)
            @McpToolParam(description = INJECTED_RUN, required = false) String runId,
            @ToolParam(description = INJECTED_OPERATION, required = false)
            @McpToolParam(description = INJECTED_OPERATION, required = false) String operationKey,
            @ToolParam(description = "连线对象数组（必填，1~200 条）：每个元素的字段同 canvas_graph_create_edge 的 edge",
                    required = true)
            @McpToolParam(description = "连线对象数组（必填，1~200 条）：每个元素的字段同 canvas_graph_create_edge 的 edge",
                    required = true)
            List<EdgeSpec> edges,
            @ToolParam(description = DETAIL_WRITE, required = false)
            @McpToolParam(description = DETAIL_WRITE, required = false) Boolean detail) {
        return run(() -> commands.createEdges(ctx(userId, projectId, canvasId, runId, operationKey),
                CanvasGraphToolInputs.toEdgeMaps(edges), Boolean.TRUE.equals(detail)));
    }

    @Tool(description = "Import durable media and create a canvas image/video node.")
    @McpTool(name = "canvas_graph_import_media",
            description = "把一份素材落成画布图片/视频节点（一次命令 = 一个 revision）。素材来源四选一："
                    + "mediaId（项目库素材，推荐）、assetId（本画布已有资产）、libraryAssetId、url（相对 /api/files/** 或 "
                    + "/api/library/media/**）。多份素材请用 canvas_graph_import_media_batch。" + PATCH_FIELD)
    public Map<String, Object> canvasGraphImportMedia(
            @ToolParam(description = INJECTED_USER, required = false)
            @McpToolParam(description = INJECTED_USER, required = false) String userId,
            @ToolParam(description = INJECTED_PROJECT, required = false)
            @McpToolParam(description = INJECTED_PROJECT, required = false) String projectId,
            @ToolParam(description = INJECTED_CANVAS, required = false)
            @McpToolParam(description = INJECTED_CANVAS, required = false) String canvasId,
            @ToolParam(description = INJECTED_RUN, required = false)
            @McpToolParam(description = INJECTED_RUN, required = false) String runId,
            @ToolParam(description = INJECTED_OPERATION, required = false)
            @McpToolParam(description = INJECTED_OPERATION, required = false) String operationKey,
            @ToolParam(description = "媒体对象：mediaId/assetId/libraryAssetId/url 至少一个，另可带 kind/name/mimeType/label/position/width/height/resultSlots/data",
                    required = true)
            @McpToolParam(description = "媒体对象：mediaId/assetId/libraryAssetId/url 至少一个，另可带 kind/name/mimeType/label/position/width/height/resultSlots/data",
                    required = true)
            MediaSpec media,
            @ToolParam(description = DETAIL_WRITE, required = false)
            @McpToolParam(description = DETAIL_WRITE, required = false) Boolean detail) {
        return run(() -> commands.importMedia(ctx(userId, projectId, canvasId, runId, operationKey),
                media == null ? Map.of() : media.toMap(), Boolean.TRUE.equals(detail)));
    }

    @Tool(description = "Import multiple durable media items and create all nodes in one graph revision.")
    @McpTool(name = "canvas_graph_import_media_batch",
            description = "一次导入多份素材（1~100 份，同一 revision）。用户一次给了多张图/多段视频时必须用这个，"
                    + "不要循环调用单份导入。每个元素可带 resultSlots（视频媒体默认 kind=video）。" + PATCH_FIELD)
    public Map<String, Object> canvasGraphImportMediaBatch(
            @ToolParam(description = INJECTED_USER, required = false)
            @McpToolParam(description = INJECTED_USER, required = false) String userId,
            @ToolParam(description = INJECTED_PROJECT, required = false)
            @McpToolParam(description = INJECTED_PROJECT, required = false) String projectId,
            @ToolParam(description = INJECTED_CANVAS, required = false)
            @McpToolParam(description = INJECTED_CANVAS, required = false) String canvasId,
            @ToolParam(description = INJECTED_RUN, required = false)
            @McpToolParam(description = INJECTED_RUN, required = false) String runId,
            @ToolParam(description = INJECTED_OPERATION, required = false)
            @McpToolParam(description = INJECTED_OPERATION, required = false) String operationKey,
            @ToolParam(description = "媒体对象数组（必填，1~100 个）：每个元素的字段同 canvas_graph_import_media 的 media",
                    required = true)
            @McpToolParam(description = "媒体对象数组（必填，1~100 个）：每个元素的字段同 canvas_graph_import_media 的 media",
                    required = true)
            List<MediaSpec> mediaItems,
            @ToolParam(description = DETAIL_WRITE, required = false)
            @McpToolParam(description = DETAIL_WRITE, required = false) Boolean detail) {
        return run(() -> commands.importMediaBatch(ctx(userId, projectId, canvasId, runId, operationKey),
                CanvasGraphToolInputs.toMediaMaps(mediaItems), Boolean.TRUE.equals(detail)));
    }

    @Tool(description = "Create ordered image/video result slots for a producer node.")
    @McpTool(name = "canvas_graph_create_result_slots",
            description = "给一个生产节点建 1~12 个有序结果槽（空图片/视频占位 + 生产节点→槽位的连线），同一 revision 完成。"
                    + "槽位名「结果 N」由服务端在整张画布内递增，不要自己指定。新节点建议直接用节点规格里的 resultSlots 字段。" + PATCH_FIELD)
    public Map<String, Object> canvasGraphCreateResultSlots(
            @ToolParam(description = INJECTED_USER, required = false)
            @McpToolParam(description = INJECTED_USER, required = false) String userId,
            @ToolParam(description = INJECTED_PROJECT, required = false)
            @McpToolParam(description = INJECTED_PROJECT, required = false) String projectId,
            @ToolParam(description = INJECTED_CANVAS, required = false)
            @McpToolParam(description = INJECTED_CANVAS, required = false) String canvasId,
            @ToolParam(description = INJECTED_RUN, required = false)
            @McpToolParam(description = INJECTED_RUN, required = false) String runId,
            @ToolParam(description = INJECTED_OPERATION, required = false)
            @McpToolParam(description = INJECTED_OPERATION, required = false) String operationKey,
            @ToolParam(description = "参数对象：producerNodeId/kind/count 必填，position/gapY 可选", required = true)
            @McpToolParam(description = "参数对象：producerNodeId/kind/count 必填，position/gapY 可选", required = true)
            ResultSlotArgs args,
            @ToolParam(description = DETAIL_WRITE, required = false)
            @McpToolParam(description = DETAIL_WRITE, required = false) Boolean detail) {
        return run(() -> commands.createResultSlots(ctx(userId, projectId, canvasId, runId, operationKey),
                args == null ? Map.of() : args.toMap(), Boolean.TRUE.equals(detail)));
    }

    @Tool(description = "Mark existing empty image/video nodes as result slots.")
    @McpTool(name = "canvas_graph_mark_result_slots",
            description = "把**已有**的空图片/视频节点标记为某个生产节点的结果槽（不新建节点，只补连线与槽位顺序）。"
                    + "已有素材的节点不能当结果槽。" + PATCH_FIELD)
    public Map<String, Object> canvasGraphMarkResultSlots(
            @ToolParam(description = INJECTED_USER, required = false)
            @McpToolParam(description = INJECTED_USER, required = false) String userId,
            @ToolParam(description = INJECTED_PROJECT, required = false)
            @McpToolParam(description = INJECTED_PROJECT, required = false) String projectId,
            @ToolParam(description = INJECTED_CANVAS, required = false)
            @McpToolParam(description = INJECTED_CANVAS, required = false) String canvasId,
            @ToolParam(description = INJECTED_RUN, required = false)
            @McpToolParam(description = INJECTED_RUN, required = false) String runId,
            @ToolParam(description = INJECTED_OPERATION, required = false)
            @McpToolParam(description = INJECTED_OPERATION, required = false) String operationKey,
            @ToolParam(description = "参数对象：producerNodeId/nodeIds 必填，startOrder 可选", required = true)
            @McpToolParam(description = "参数对象：producerNodeId/nodeIds 必填，startOrder 可选", required = true)
            MarkResultSlotArgs args,
            @ToolParam(description = DETAIL_WRITE, required = false)
            @McpToolParam(description = DETAIL_WRITE, required = false) Boolean detail) {
        return run(() -> commands.markResultSlots(ctx(userId, projectId, canvasId, runId, operationKey),
                args == null ? Map.of() : args.toMap(), Boolean.TRUE.equals(detail)));
    }

    @Tool(description = "Lay out canvas nodes using grid/horizontal/vertical/dag mode.")
    @McpTool(name = "canvas_graph_layout_graph",
            description = "整理画布布局（grid 默认 / horizontal / vertical / dag 按依赖分层），一次 revision。"
                    + "只排布 nodeIds 指定的节点，省略则排布整张画布；anchor 省略时取参与节点最小 x/y。" + PATCH_FIELD)
    public Map<String, Object> canvasGraphLayoutGraph(
            @ToolParam(description = INJECTED_USER, required = false)
            @McpToolParam(description = INJECTED_USER, required = false) String userId,
            @ToolParam(description = INJECTED_PROJECT, required = false)
            @McpToolParam(description = INJECTED_PROJECT, required = false) String projectId,
            @ToolParam(description = INJECTED_CANVAS, required = false)
            @McpToolParam(description = INJECTED_CANVAS, required = false) String canvasId,
            @ToolParam(description = INJECTED_RUN, required = false)
            @McpToolParam(description = INJECTED_RUN, required = false) String runId,
            @ToolParam(description = INJECTED_OPERATION, required = false)
            @McpToolParam(description = INJECTED_OPERATION, required = false) String operationKey,
            @ToolParam(description = "布局参数：mode/nodeIds/gapX/gapY/columns/anchor 全部可选", required = true)
            @McpToolParam(description = "布局参数：mode/nodeIds/gapX/gapY/columns/anchor 全部可选", required = true)
            LayoutSpec args,
            @ToolParam(description = DETAIL_WRITE, required = false)
            @McpToolParam(description = DETAIL_WRITE, required = false) Boolean detail) {
        return run(() -> commands.layoutGraph(ctx(userId, projectId, canvasId, runId, operationKey),
                args == null ? Map.of() : args.toMap(), Boolean.TRUE.equals(detail)));
    }

    @Tool(description = "Submit a whole canvas build plan in one CAS transaction.")
    @McpTool(name = "canvas_graph_apply_plan",
            description = "**建图首选**：一次提交整张图的构建意图（节点 + 内联素材 + refs 自动连线 + 结果槽 + 布局），"
                    + "只产生 1 个 revision、只重载一次画布。用法：给每个节点一个 ref（如 \"g1\"），"
                    + "再用 refs 声明它的上游来源——\"m1\" 表示引用该节点输出，\"g1.result.0\" 表示引用 g1 的第 1 个结果槽输出；"
                    + "端口与有序位次（reference/first/last/clips 的 portOrder）由服务端按端口表自动选择，按 refs 数组顺序占用。"
                    + "**本轮要给素材时不要再单独调 canvas_graph_import_media_batch**：直接在节点上写 "
                    + "media:{mediaId 或 url, name?, mimeType?, label?}（mediaId 与 url 二选一），服务端会在同一次提交里把素材落成"
                    + " image/video 节点，该节点的 ref 立刻可以被别的节点 refs 引用；同一份素材重复出现只建一个节点。"
                    + "带 media 的节点不要填 type、也不要声明 resultSlots（素材节点不是生产节点）。"
                    + "生成节点（imageGeneration/videoGeneration）的输出必须先接结果槽，所以下游要用 \"<ref>.result.<n>\" 引用它。"
                    + "plan.layout 存在时在本批次变更之后、同一次保存内排布（不额外增加 revision）。"
                    + "校验失败策略：continueOnError=false（默认）会把**全部**错误汇总到 error.details.errors=[{index|ref,code,message}] "
                    + "且整批不落库；continueOnError=true 则跳过失败项、落库成功项，返回 errors/skipped/partial=true。" + PATCH_FIELD)
    public Map<String, Object> canvasGraphApplyPlan(
            @ToolParam(description = INJECTED_USER, required = false)
            @McpToolParam(description = INJECTED_USER, required = false) String userId,
            @ToolParam(description = INJECTED_PROJECT, required = false)
            @McpToolParam(description = INJECTED_PROJECT, required = false) String projectId,
            @ToolParam(description = INJECTED_CANVAS, required = false)
            @McpToolParam(description = INJECTED_CANVAS, required = false) String canvasId,
            @ToolParam(description = INJECTED_RUN, required = false)
            @McpToolParam(description = INJECTED_RUN, required = false) String runId,
            @ToolParam(description = INJECTED_OPERATION, required = false)
            @McpToolParam(description = INJECTED_OPERATION, required = false) String operationKey,
            @ToolParam(description = "计划对象（必填）：nodes（1~100 个，必填，每个节点可选 media 内联素材与 refs/resultSlots）、"
                    + "edges（可选显式边）、layout（可选收尾布局）、pruneUnreferencedResultSlots（可选，默认 false）", required = true)
            @McpToolParam(description = "计划对象（必填）：nodes（1~100 个，必填，每个节点可选 media 内联素材与 refs/resultSlots）、"
                    + "edges（可选显式边）、layout（可选收尾布局）、pruneUnreferencedResultSlots（可选，默认 false）", required = true)
            PlanSpec plan,
            @ToolParam(description = "校验失败是否继续（可选，默认 false=整批不落库并返回全部错误；true=跳过失败项、落库成功项）",
                    required = false)
            @McpToolParam(description = "校验失败是否继续（可选，默认 false=整批不落库并返回全部错误；true=跳过失败项、落库成功项）",
                    required = false)
            Boolean continueOnError,
            @ToolParam(description = DETAIL_WRITE, required = false)
            @McpToolParam(description = DETAIL_WRITE, required = false) Boolean detail) {
        return run(() -> commands.applyPlan(ctx(userId, projectId, canvasId, runId, operationKey),
                plan == null ? Map.of() : plan.toMap(),
                Boolean.TRUE.equals(continueOnError),
                Boolean.TRUE.equals(detail)));
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
