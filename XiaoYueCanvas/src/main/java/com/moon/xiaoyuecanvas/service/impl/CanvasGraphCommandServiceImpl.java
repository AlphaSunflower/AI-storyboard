package com.moon.xiaoyuecanvas.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.entity.MorrowCanvas;
import com.moon.moonagent.security.FileUrlSigner;
import com.moon.moonagent.service.FileStorageService;
import com.moon.moonagent.service.MorrowCanvasService;
import com.moon.xiaoyuecanvas.dto.response.CanvasGraphResponse;
import com.moon.xiaoyuecanvas.entity.CanvasAsset;
import com.moon.xiaoyuecanvas.entity.CanvasGraphOperation;
import com.moon.xiaoyuecanvas.mapper.CanvasAssetMapper;
import com.moon.xiaoyuecanvas.mapper.CanvasGraphOperationMapper;
import com.moon.xiaoyuecanvas.service.CanvasAssetService;
import com.moon.xiaoyuecanvas.service.CanvasGraphCommandContext;
import com.moon.xiaoyuecanvas.service.CanvasGraphCommandException;
import com.moon.xiaoyuecanvas.service.CanvasGraphCommandService;
import com.moon.xiaoyuecanvas.service.CanvasNodeRegistry;
import com.moon.xiaoyuecanvas.service.CanvasProjectLibraryService;
import com.moon.xiaoyuecanvas.service.CanvasGraphService;
import com.moon.xiaoyuecanvas.util.JsonUtil;
import com.storyboard.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class CanvasGraphCommandServiceImpl implements CanvasGraphCommandService {

    private static final String FILE_URL_PREFIX = "/api/files/";
    private static final int MAX_BATCH_NODES = 100;
    /** 单次批量连线上限：够一轮建完整条链路，又不至于让一次 CAS 载荷过大。 */
    private static final int MAX_BATCH_EDGES = 200;
    /** 结果槽单次数量上限（与既有 create_result_slots 校验保持一致）。 */
    private static final int MAX_RESULT_SLOTS = 12;
    /** 读工具默认分页大小（list_nodes / list_edges / get_state）。 */
    private static final int DEFAULT_PAGE_SIZE = 100;
    /** 结果槽默认命名「结果 N」；编号在整张画布内递增，避免每个生产节点都从「结果 1」重来。 */
    private static final Pattern RESULT_SLOT_LABEL = Pattern.compile("^结果\\s*(\\d+)$");
    /** refs 表达式里「第 n 个结果槽」的写法：{@code <ref>.result.<n>}。 */
    private static final Pattern RESULT_REF = Pattern.compile("^(.+?)\\.result\\.(\\d+)$");
    /** 紧凑节点视图保留的 data 键：够画布渲染与模型判断，不含长文本 prompt 与带签名的 assetUrl。 */
    private static final List<String> COMPACT_NODE_DATA_KEYS = List.of("label", "mode", "status", "producerNodeId");
    /** 紧凑连线视图保留的 data 键。 */
    private static final List<String> COMPACT_EDGE_DATA_KEYS =
            List.of("portOrder", "resultSlot", "resultSlotOrder");
    /**
     * 节点规格顶层可以直接写的 data 字段（工具层的扁平写法）：与 {@code data.xxx} 等价，
     * data 里已显式给出的同名键优先。
     */
    private static final List<String> NODE_DATA_FLAT_KEYS = List.of("label", "mode", "prompt", "status");

    private final CanvasGraphService graphService;
    private final CanvasGraphOperationMapper operationMapper;
    private final MorrowCanvasService canvasService;
    private final CanvasAssetService assetService;
    private final CanvasAssetMapper assetMapper;
    private final CanvasProjectLibraryService libraryService;
    private final FileStorageService fileStorageService;
    private final FileUrlSigner fileUrlSigner;
    private final String internalStoryboardUrl;

    public CanvasGraphCommandServiceImpl(
            CanvasGraphService graphService,
            CanvasGraphOperationMapper operationMapper,
            MorrowCanvasService canvasService,
            CanvasAssetService assetService,
            CanvasAssetMapper assetMapper,
            CanvasProjectLibraryService libraryService,
            FileStorageService fileStorageService,
            FileUrlSigner fileUrlSigner,
            @Value("${storyboard.internal-url:http://localhost:8082}") String internalStoryboardUrl
    ) {
        this.graphService = graphService;
        this.operationMapper = operationMapper;
        this.canvasService = canvasService;
        this.assetService = assetService;
        this.assetMapper = assetMapper;
        this.libraryService = libraryService;
        this.fileStorageService = fileStorageService;
        this.fileUrlSigner = fileUrlSigner;
        this.internalStoryboardUrl = trimSlash(internalStoryboardUrl);
    }

    @Override
    public Map<String, Object> getContext(CanvasGraphCommandContext context) {
        MorrowCanvas canvas = requireCanvas(context);
        CanvasGraphResponse loaded = load(context);
        Map<String, Object> out = ok("get_context");
        out.put("canvasId", canvas.getId());
        out.put("projectId", canvas.getProjectId());
        out.put("title", canvas.getTitle());
        out.put("revision", loaded.revision());
        out.put("nodeCount", list(loaded.graph().get("nodes")).size());
        out.put("edgeCount", list(loaded.graph().get("edges")).size());
        putRun(out, context);
        return out;
    }

    @Override
    public Map<String, Object> getState(
            CanvasGraphCommandContext context,
            Integer limit,
            Integer offset,
            Boolean includeEdges,
            boolean detail
    ) {
        requireCanvas(context);
        CanvasGraphResponse loaded = load(context);
        Map<String, Object> full = mutableGraph(loaded.graph());
        List<Map<String, Object>> allNodes = listOfMaps(full.get("nodes"));
        List<Map<String, Object>> allEdges = listOfMaps(full.get("edges"));
        List<Map<String, Object>> visibleNodes = slice(allNodes, limit, offset);
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("schemaVersion", 2);
        graph.put("viewport", mapOrEmpty(full.get("viewport")));
        graph.put("nodes", detail ? visibleNodes
                : visibleNodes.stream().map(CanvasGraphCommandServiceImpl::compactNode).toList());
        boolean withEdges = !Boolean.FALSE.equals(includeEdges);
        graph.put("edges", !withEdges ? new ArrayList<>()
                : detail ? new ArrayList<>(allEdges)
                : allEdges.stream().map(CanvasGraphCommandServiceImpl::compactEdge).toList());
        if (detail) graph.put("generationRecords", listOfMaps(full.get("generationRecords")));

        Map<String, Object> out = ok("get_state");
        out.put("canvasId", context.canvasId());
        out.put("projectId", context.projectId());
        out.put("revision", loaded.revision());
        out.put("updatedAt", loaded.updatedAt());
        // nodeCount/edgeCount 保持「整张画布」口径，分页只影响 graph.nodes/graph.edges
        out.put("nodeCount", allNodes.size());
        out.put("edgeCount", allEdges.size());
        out.put("returnedNodeCount", visibleNodes.size());
        out.put("detail", detail);
        out.put("graph", graph);
        putRun(out, context);
        return out;
    }

    @Override
    public Map<String, Object> listNodes(CanvasGraphCommandContext context,
                                         List<String> nodeIds,
                                         Integer limit,
                                         Integer offset,
                                         boolean detail) {
        requireCanvas(context);
        CanvasGraphResponse loaded = load(context);
        Set<String> filter = nodeIds == null ? Set.of() : new HashSet<>(nodeIds);
        List<Map<String, Object>> all = listOfMaps(loaded.graph().get("nodes")).stream()
                .filter(node -> filter.isEmpty() || filter.contains(string(node.get("id"))))
                .toList();
        Map<String, Object> out = ok("list_nodes");
        out.put("revision", loaded.revision());
        out.put("total", all.size());
        out.put("count", all.size());
        out.put("limit", pageSize(limit));
        out.put("offset", pageOffset(offset));
        out.put("detail", detail);
        out.put("nodes", detail ? page(all, limit, offset)
                : page(all, limit, offset).stream().map(CanvasGraphCommandServiceImpl::compactNode).toList());
        putRun(out, context);
        return out;
    }

    @Override
    public Map<String, Object> getNode(CanvasGraphCommandContext context, String nodeId) {
        requireCanvas(context);
        CanvasGraphResponse loaded = load(context);
        Map<String, Object> node = findNode(loaded.graph(), nodeId);
        if (node == null) {
            throw new CanvasGraphCommandException("NODE_NOT_FOUND", "节点不存在: " + nodeId,
                    Map.of("nodeId", nodeId));
        }
        Map<String, Object> out = ok("get_node");
        out.put("revision", loaded.revision());
        out.put("node", node);
        putRun(out, context);
        return out;
    }

    @Override
    public Map<String, Object> listEdges(CanvasGraphCommandContext context,
                                         List<String> edgeIds,
                                         Integer limit,
                                         Integer offset,
                                         boolean detail) {
        requireCanvas(context);
        CanvasGraphResponse loaded = load(context);
        Set<String> filter = edgeIds == null ? Set.of() : new HashSet<>(edgeIds);
        List<Map<String, Object>> all = listOfMaps(loaded.graph().get("edges")).stream()
                .filter(edge -> filter.isEmpty() || filter.contains(string(edge.get("id"))))
                .toList();
        Map<String, Object> out = ok("list_edges");
        out.put("revision", loaded.revision());
        out.put("total", all.size());
        out.put("count", all.size());
        out.put("limit", pageSize(limit));
        out.put("offset", pageOffset(offset));
        out.put("detail", detail);
        out.put("edges", detail ? page(all, limit, offset)
                : page(all, limit, offset).stream().map(CanvasGraphCommandServiceImpl::compactEdge).toList());
        putRun(out, context);
        return out;
    }

    @Override
    public Map<String, Object> listNodeTypes(CanvasGraphCommandContext context) {
        if (!blank(context.canvasId())) {
            requireCanvas(context);
        }
        Map<String, Object> out = ok("list_node_types");
        out.put("nodeTypes", CanvasNodeRegistry.listMaps());
        putRun(out, context);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> createNode(CanvasGraphCommandContext context,
                                          Map<String, Object> node,
                                          boolean detail) {
        return idempotent(context, "create_node", () -> {
            requireCanvas(context);
            CanvasGraphResponse loaded = load(context);
            Map<String, Object> graph = mutableGraph(loaded.graph());
            Map<String, Object> created = createNodeInternal(graph, node);
            ResultSlotBatch slots = createResultSlotsForNode(graph, created, node);
            int revision = saveGraph(context, graph, loaded.revision());
            Map<String, Object> out = writeResult("create_node", context, revision, graph);
            out.put("nodeId", created.get("id"));
            out.put("nodeIds", List.of(string(created.get("id"))));
            if (!slots.nodeIds().isEmpty()) out.put("resultSlotIds", slots.nodeIds());
            List<Map<String, Object>> patchNodes = new ArrayList<>();
            patchNodes.add(created);
            patchNodes.addAll(slots.nodes());
            out.put("patch", changePatch(patchNodes, slots.edges(), List.of(), List.of()));
            if (detail) {
                out.put("node", created);
                if (!slots.nodes().isEmpty()) out.put("nodes", slots.nodes());
            }
            return out;
        });
    }

    @Override
    @Transactional
    public Map<String, Object> createNodes(CanvasGraphCommandContext context,
                                           List<Map<String, Object>> items,
                                           boolean detail) {
        return idempotent(context, "create_nodes", () -> {
            if (items == null || items.isEmpty()) {
                throw new CanvasGraphCommandException("INVALID_ARGUMENTS", "items 不能为空");
            }
            if (items.size() > MAX_BATCH_NODES) {
                throw new CanvasGraphCommandException("LIMIT_EXCEEDED",
                        "单次最多创建 " + MAX_BATCH_NODES + " 个节点");
            }
            requireCanvas(context);
            CanvasGraphResponse loaded = load(context);
            Map<String, Object> graph = mutableGraph(loaded.graph());
            List<Map<String, Object>> created = new ArrayList<>();
            List<Map<String, Object>> patchNodes = new ArrayList<>();
            List<Map<String, Object>> patchEdges = new ArrayList<>();
            List<String> slotIds = new ArrayList<>();
            for (Map<String, Object> item : items) {
                Map<String, Object> node = createNodeInternal(graph, item);
                created.add(node);
                patchNodes.add(node);
                // 每个 item 的 resultSlots 立刻建槽：编号在整张画布内递增，同一批也连续
                ResultSlotBatch slots = createResultSlotsForNode(graph, node, item);
                patchNodes.addAll(slots.nodes());
                patchEdges.addAll(slots.edges());
                slotIds.addAll(slots.nodeIds());
            }
            int revision = saveGraph(context, graph, loaded.revision());
            Map<String, Object> out = writeResult("create_nodes", context, revision, graph);
            out.put("nodeIds", created.stream().map(node -> string(node.get("id"))).toList());
            if (!slotIds.isEmpty()) out.put("resultSlotIds", slotIds);
            out.put("patch", changePatch(patchNodes, patchEdges, List.of(), List.of()));
            if (detail) out.put("nodes", created);
            return out;
        });
    }

    @Override
    @Transactional
    public Map<String, Object> updateNode(
            CanvasGraphCommandContext context,
            String nodeId,
            Map<String, Object> patch,
            boolean detail
    ) {
        return idempotent(context, "update_node", () -> {
            requireCanvas(context);
            if (blank(nodeId)) {
                throw new CanvasGraphCommandException("INVALID_ARGUMENTS", "nodeId 不能为空");
            }
            if (patch == null || patch.isEmpty()) {
                throw new CanvasGraphCommandException("INVALID_ARGUMENTS", "patch 不能为空");
            }
            CanvasGraphResponse loaded = load(context);
            Map<String, Object> graph = mutableGraph(loaded.graph());
            // 必须用「就地」查找：findNode 经 listOfMaps 返回副本，改副本不会落库
            Map<String, Object> node = findLiveNode(graph, nodeId);
            if (node == null) {
                throw new CanvasGraphCommandException("NODE_NOT_FOUND", "节点不存在: " + nodeId);
            }
            Map<String, Object> data = mutableMap(node.get("data"));
            patch.forEach((key, value) -> {
                if (Set.of("id", "type", "kind").contains(key)) return;
                // 扁平 {label:"x"} 与嵌套 {data:{label:"x"}} 等价，都 merge 进同一层 data。
                // 旧实现把 data 键原样塞进 data，模型传 {data:{...}} 会写成 data.data.label。
                if ("data".equals(key)) {
                    mutableMap(value).forEach((nestedKey, nestedValue) -> {
                        if (!Set.of("id", "type", "kind").contains(nestedKey)) data.put(nestedKey, nestedValue);
                    });
                    return;
                }
                data.put(key, value);
            });
            node.put("data", data);
            int revision = saveGraph(context, graph, loaded.revision());
            Map<String, Object> out = writeResult("update_node", context, revision, graph);
            out.put("nodeId", nodeId);
            out.put("patch", changePatch(List.of(node), List.of(), List.of(), List.of()));
            if (detail) out.put("node", node);
            return out;
        });
    }

    @Override
    @Transactional
    public Map<String, Object> createEdge(CanvasGraphCommandContext context,
                                          Map<String, Object> edge,
                                          boolean detail) {
        return idempotent(context, "create_edge", () -> {
            requireCanvas(context);
            CanvasGraphResponse loaded = load(context);
            Map<String, Object> graph = mutableGraph(loaded.graph());
            Map<String, Object> created = createEdgeInternal(graph, edge);
            int revision = saveGraph(context, graph, loaded.revision());
            Map<String, Object> out = writeResult("create_edge", context, revision, graph);
            out.put("edgeId", created.get("id"));
            out.put("edgeIds", List.of(string(created.get("id"))));
            out.put("patch", changePatch(List.of(), List.of(created), List.of(), List.of()));
            if (detail) out.put("edge", created);
            return out;
        });
    }

    /**
     * 批量建边：一次命令、一次 saveGraph（revision 只 +1）。
     *
     * <p>all-or-nothing：任一条校验失败则整批不落库，并一次性返回**全部**失败项，
     * 让模型一轮改完（而不是错一条改一轮）。已有的完全相同连线视为幂等，不报错。
     */
    @Override
    @Transactional
    public Map<String, Object> createEdges(CanvasGraphCommandContext context,
                                           List<Map<String, Object>> edges,
                                           boolean detail) {
        return idempotent(context, "create_edges", () -> {
            if (edges == null || edges.isEmpty()) {
                throw new CanvasGraphCommandException("INVALID_ARGUMENTS", "edges 不能为空");
            }
            if (edges.size() > MAX_BATCH_EDGES) {
                throw new CanvasGraphCommandException("LIMIT_EXCEEDED",
                        "单次最多创建 " + MAX_BATCH_EDGES + " 条连线");
            }
            requireCanvas(context);
            CanvasGraphResponse loaded = load(context);
            Map<String, Object> graph = mutableGraph(loaded.graph());
            List<Map<String, Object>> created = new ArrayList<>();
            List<String> edgeIds = new ArrayList<>();
            List<Map<String, Object>> errors = new ArrayList<>();
            for (int index = 0; index < edges.size(); index++) {
                Map<String, Object> spec = edges.get(index);
                try {
                    String existingId = existingEdgeId(graph,
                            string(spec.get("source")), string(spec.get("sourceHandle")),
                            string(spec.get("target")), string(spec.get("targetHandle")));
                    if (existingId != null) {
                        edgeIds.add(existingId);
                        continue;
                    }
                    Map<String, Object> edge = createEdgeInternal(graph, spec);
                    created.add(edge);
                    edgeIds.add(string(edge.get("id")));
                } catch (CanvasGraphCommandException error) {
                    errors.add(batchError(index, null, error));
                }
            }
            // 部分失败 = 整批不落库：图只改在内存里，直接抛出，不产生 revision
            if (!errors.isEmpty()) {
                throw new CanvasGraphCommandException("EDGE_BATCH_INVALID",
                        "有 " + errors.size() + " 条连线未通过校验，整批未落库",
                        Map.of("errors", errors, "failedCount", errors.size(), "total", edges.size()));
            }
            int revision = saveGraph(context, graph, loaded.revision());
            Map<String, Object> out = writeResult("create_edges", context, revision, graph);
            out.put("edgeIds", edgeIds);
            out.put("patch", changePatch(List.of(), created, List.of(), List.of()));
            if (detail) out.put("edges", created);
            return out;
        });
    }

    @Override
    @Transactional
    public Map<String, Object> importMedia(CanvasGraphCommandContext context,
                                           Map<String, Object> media,
                                           boolean detail) {
        return idempotent(context, "import_media", () -> {
            MorrowCanvas canvas = requireCanvas(context);
            CanvasGraphResponse loaded = load(context);
            Map<String, Object> graph = mutableGraph(loaded.graph());
            ImportedMedia imported = importMediaIntoGraph(context, canvas, graph, media);
            ResultSlotBatch slots = createResultSlotsForNode(graph, imported.node(), media);
            int revision = saveGraph(context, graph, loaded.revision());

            Map<String, Object> out = writeResult("import_media", context, revision, graph);
            out.put("nodeId", imported.node().get("id"));
            out.put("nodeIds", List.of(string(imported.node().get("id"))));
            out.put("assetId", imported.asset().getId());
            if (!slots.nodeIds().isEmpty()) out.put("resultSlotIds", slots.nodeIds());
            List<Map<String, Object>> patchNodes = new ArrayList<>();
            patchNodes.add(imported.node());
            patchNodes.addAll(slots.nodes());
            out.put("patch", changePatch(patchNodes, slots.edges(), List.of(), List.of()));
            if (detail) {
                out.put("node", imported.node());
                out.put("asset", assetMap(imported.asset()));
                if (!slots.nodes().isEmpty()) out.put("nodes", slots.nodes());
            }
            return out;
        });
    }

    @Override
    @Transactional
    public Map<String, Object> importMediaBatch(
            CanvasGraphCommandContext context,
            List<Map<String, Object>> mediaItems,
            boolean detail
    ) {
        return idempotent(context, "import_media_batch", () -> {
            if (mediaItems == null || mediaItems.isEmpty()) {
                throw new CanvasGraphCommandException("INVALID_ARGUMENTS", "mediaItems 不能为空");
            }
            if (mediaItems.size() > MAX_BATCH_NODES) {
                throw new CanvasGraphCommandException("LIMIT_EXCEEDED",
                        "单次最多导入 " + MAX_BATCH_NODES + " 个媒体");
            }
            MorrowCanvas canvas = requireCanvas(context);
            CanvasGraphResponse loaded = load(context);
            Map<String, Object> graph = mutableGraph(loaded.graph());
            List<String> nodeIds = new ArrayList<>();
            List<String> assetIds = new ArrayList<>();
            List<Map<String, Object>> nodes = new ArrayList<>();
            List<Map<String, Object>> assets = new ArrayList<>();
            List<Map<String, Object>> patchNodes = new ArrayList<>();
            List<Map<String, Object>> patchEdges = new ArrayList<>();
            List<String> slotIds = new ArrayList<>();
            for (Map<String, Object> media : mediaItems) {
                ImportedMedia imported = importMediaIntoGraph(context, canvas, graph, media);
                nodeIds.add(string(imported.node().get("id")));
                assetIds.add(imported.asset().getId());
                nodes.add(imported.node());
                assets.add(assetMap(imported.asset()));
                patchNodes.add(imported.node());
                // 媒体 item 的 resultSlots：图片/视频占位节点各自只有一种媒体输出，kind 可省略
                ResultSlotBatch slots = createResultSlotsForNode(graph, imported.node(), media);
                patchNodes.addAll(slots.nodes());
                patchEdges.addAll(slots.edges());
                slotIds.addAll(slots.nodeIds());
            }
            int revision = saveGraph(context, graph, loaded.revision());
            Map<String, Object> out = writeResult("import_media_batch", context, revision, graph);
            out.put("nodeIds", nodeIds);
            out.put("assetIds", assetIds);
            if (!slotIds.isEmpty()) out.put("resultSlotIds", slotIds);
            out.put("patch", changePatch(patchNodes, patchEdges, List.of(), List.of()));
            if (detail) {
                out.put("nodes", nodes);
                out.put("assets", assets);
            }
            return out;
        });
    }

    @Override
    @Transactional
    public Map<String, Object> createResultSlots(CanvasGraphCommandContext context,
                                                 Map<String, Object> args,
                                                 boolean detail) {
        return idempotent(context, "create_result_slots", () -> {
            requireCanvas(context);
            CanvasGraphResponse loaded = load(context);
            Map<String, Object> graph = mutableGraph(loaded.graph());
            ResultSlotBatch slots = createResultSlotsInternal(
                    graph,
                    string(args.get("producerNodeId")),
                    normalizeMediaKind(string(args.get("kind"))),
                    integer(args.get("count"), 0),
                    map(args.get("position")),
                    args.get("gapY"));
            int revision = saveGraph(context, graph, loaded.revision());
            Map<String, Object> out = writeResult("create_result_slots", context, revision, graph);
            out.put("nodeIds", slots.nodeIds());
            out.put("edgeIds", slots.edgeIds());
            out.put("patch", changePatch(slots.nodes(), slots.edges(), List.of(), List.of()));
            if (detail) {
                out.put("nodes", slots.nodes());
                out.put("edges", slots.edges());
            }
            return out;
        });
    }

    @Override
    @Transactional
    public Map<String, Object> markResultSlots(CanvasGraphCommandContext context,
                                               Map<String, Object> args,
                                               boolean detail) {
        return idempotent(context, "mark_result_slots", () -> {
            requireCanvas(context);
            String producerNodeId = string(args.get("producerNodeId"));
            List<String> nodeIds = strings(args.get("nodeIds"));
            int startOrder = integer(args.get("startOrder"), 0);
            if (blank(producerNodeId) || nodeIds.isEmpty()) {
                throw new CanvasGraphCommandException("INVALID_ARGUMENTS", "producerNodeId 和 nodeIds 不能为空");
            }

            CanvasGraphResponse loaded = load(context);
            Map<String, Object> graph = mutableGraph(loaded.graph());
            Map<String, Object> producer = findNode(graph, producerNodeId);
            if (producer == null) {
                throw new CanvasGraphCommandException("NODE_NOT_FOUND", "生产节点不存在: " + producerNodeId);
            }
            CanvasNodeRegistry.NodeType producerType = CanvasNodeRegistry.require(kindOf(producer));
            List<String> edgeIds = new ArrayList<>();
            List<Map<String, Object>> markedEdges = new ArrayList<>();
            for (int index = 0; index < nodeIds.size(); index++) {
                String nodeId = nodeIds.get(index);
                Map<String, Object> target = findNode(graph, nodeId);
                if (target == null) {
                    throw new CanvasGraphCommandException("NODE_NOT_FOUND", "结果槽节点不存在: " + nodeId);
                }
                String targetKind = kindOf(target);
                if (!"image".equals(targetKind) && !"video".equals(targetKind)) {
                    throw new CanvasGraphCommandException("INVALID_ARGUMENTS",
                            "节点 " + nodeId + " 不是图片或视频占位节点");
                }
                if (!blank(string(mutableMap(target.get("data")).get("assetId")))) {
                    throw new CanvasGraphCommandException("INVALID_ARGUMENTS",
                            "节点 " + nodeId + " 已有素材，不能作为空结果槽");
                }
                CanvasNodeRegistry.Port output = producerType.outputs().stream()
                        .filter(port -> targetKind.equals(port.media()))
                        .findFirst()
                        .orElseThrow(() -> new CanvasGraphCommandException(
                                "PORT_NOT_FOUND", "生产节点没有 " + targetKind + " 输出"));
                Map<String, Object> existing = findLiveEdge(graph, producerNodeId, nodeId);
                int order = startOrder + index;
                if (existing != null) {
                    Map<String, Object> data = mutableMap(existing.get("data"));
                    data.put("resultSlot", true);
                    data.put("resultSlotOrder", order);
                    existing.put("data", data);
                    edgeIds.add(string(existing.get("id")));
                    markedEdges.add(existing);
                } else {
                    Map<String, Object> edge = createEdgeInternal(graph, Map.of(
                            "source", producerNodeId,
                            "sourceHandle", output.id(),
                            "target", nodeId,
                            "targetHandle", "origin",
                            "data", Map.of("resultSlot", true, "resultSlotOrder", order)));
                    edgeIds.add(string(edge.get("id")));
                    markedEdges.add(edge);
                }
            }
            int revision = saveGraph(context, graph, loaded.revision());
            Map<String, Object> out = writeResult("mark_result_slots", context, revision, graph);
            out.put("nodeIds", nodeIds);
            out.put("edgeIds", edgeIds);
            List<Map<String, Object>> markedNodes = nodeIds.stream()
                    .map(id -> findNode(graph, id))
                    .filter(Objects::nonNull)
                    .toList();
            out.put("patch", changePatch(markedNodes, markedEdges, List.of(), List.of()));
            if (detail) {
                out.put("nodes", markedNodes);
                out.put("edges", markedEdges);
            }
            return out;
        });
    }

    @Override
    @Transactional
    public Map<String, Object> layoutGraph(CanvasGraphCommandContext context,
                                           Map<String, Object> args,
                                           boolean detail) {
        return idempotent(context, "layout_graph", () -> {
            requireCanvas(context);
            CanvasGraphResponse loaded = load(context);
            Map<String, Object> graph = mutableGraph(loaded.graph());
            List<Map<String, Object>> nodes = listOfMaps(graph.get("nodes"));
            if (nodes.isEmpty()) {
                Map<String, Object> out = writeResult("layout_graph", context, loaded.revision(), graph);
                out.put("movedNodeIds", List.of());
                out.put("positions", List.of());
                out.put("patch", changePatch(List.of(), List.of(), List.of(), List.of()));
                return out;
            }
            LayoutOutcome layout = applyLayout(graph, args);
            int revision = saveGraph(context, graph, loaded.revision());
            Map<String, Object> out = writeResult("layout_graph", context, revision, graph);
            out.put("movedNodeIds", layout.movedNodeIds());
            out.put("positions", layout.positions());
            List<Map<String, Object>> movedNodes = layout.movedNodeIds().stream()
                    .map(id -> findNode(graph, id))
                    .filter(Objects::nonNull)
                    .toList();
            out.put("patch", changePatch(movedNodes, List.of(), List.of(), List.of()));
            if (detail) out.put("nodes", movedNodes);
            return out;
        });
    }

    /**
     * 在已加载的图对象上执行一次布局（调用方负责 saveGraph，因此不会额外产生 revision）。
     *
     * <p>nodeIds 为空时排布整张画布；锚点缺省取选区内最小 x/y。
     */
    private static LayoutOutcome applyLayout(Map<String, Object> graph, Map<String, Object> args) {
        // 就地取节点：listOfMaps 返回的是副本，改副本不会落库（既存 layout 静默无效的根因）
        List<Map<String, Object>> nodes = liveElements(graph, "nodes");
        Map<String, Object> options = mapOrEmpty(args);
        Set<String> selected = new HashSet<>(strings(options.get("nodeIds")));
        List<Map<String, Object>> scoped = nodes.stream()
                .filter(node -> selected.isEmpty() || selected.contains(string(node.get("id"))))
                .toList();
        if (scoped.isEmpty()) {
            throw new CanvasGraphCommandException("NODE_NOT_FOUND", "没有可排布的节点");
        }
        String mode = firstNonBlank(string(options.get("mode")), "grid");
        double gapX = number(options.get("gapX"), 520);
        double gapY = number(options.get("gapY"), 420);
        double baseX = number(mapOrEmpty(options.get("anchor")).get("x"), minPosition(scoped, "x"));
        double baseY = number(mapOrEmpty(options.get("anchor")).get("y"), minPosition(scoped, "y"));
        List<LayoutPosition> positions = "dag".equals(mode)
                ? dagLayout(graph, scoped, baseX, baseY, gapX, gapY)
                : simpleLayout(scoped, mode, baseX, baseY, gapX, gapY, integer(options.get("columns"), 0));
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> node : nodes) byId.put(string(node.get("id")), node);
        List<Map<String, Object>> serialized = new ArrayList<>();
        List<String> moved = new ArrayList<>();
        for (LayoutPosition position : positions) {
            Map<String, Object> node = byId.get(position.nodeId());
            if (node == null) continue;
            Map<String, Object> old = map(node.get("position"));
            if (old == null || number(old.get("x"), 0) != position.x() || number(old.get("y"), 0) != position.y()) {
                node.put("position", Map.of("x", position.x(), "y", position.y()));
                moved.add(position.nodeId());
            }
            serialized.add(Map.of("nodeId", position.nodeId(), "x", position.x(), "y", position.y()));
        }
        return new LayoutOutcome(moved, serialized);
    }

    /**
     * 一次提交整张图的构建意图：节点 + 内联素材导入 + refs 自动连线 + 结果槽 + 布局，只产生一个 revision。
     *
     * <p>节点规格可带 {@code media:{mediaId?|url?,name?,mimeType?,label?}} 把素材直接折进本次提交：
     * 复用 {@code importMediaBatch} 的同一条素材导入路径，按素材类型生成 image/video 节点，节点带 ref 即可
     * 被其它节点的 {@code refs} 引用；同一份素材（mediaId/url）在一个 plan 里只建一个节点。
     *
     * <p>错误策略：{@code continueOnError=false} 时把节点/边/端口/环/重复/素材校验**全部跑完**再一次性
     * 抛出（不落库，图保持原样）；{@code true} 时跳过失败项、落库成功项并返回 errors/skipped。
     */
    @Override
    @Transactional
    public Map<String, Object> applyPlan(CanvasGraphCommandContext context,
                                         Map<String, Object> plan,
                                         boolean continueOnError,
                                         boolean detail) {
        return idempotent(context, "apply_plan", () -> {
            Map<String, Object> payload = map(plan);
            if (payload == null) {
                throw new CanvasGraphCommandException("INVALID_ARGUMENTS", "plan 不能为空");
            }
            List<Map<String, Object>> nodeSpecs = listOfMaps(payload.get("nodes"));
            if (nodeSpecs.isEmpty()) {
                throw new CanvasGraphCommandException("INVALID_ARGUMENTS", "plan.nodes 不能为空");
            }
            if (nodeSpecs.size() > MAX_BATCH_NODES) {
                throw new CanvasGraphCommandException("LIMIT_EXCEEDED",
                        "单次最多创建 " + MAX_BATCH_NODES + " 个节点");
            }
            List<Map<String, Object>> edgeSpecs = listOfMaps(payload.get("edges"));
            if (edgeSpecs.size() > MAX_BATCH_EDGES) {
                throw new CanvasGraphCommandException("LIMIT_EXCEEDED",
                        "单次最多创建 " + MAX_BATCH_EDGES + " 条连线");
            }
            MorrowCanvas canvas = requireCanvas(context);
            CanvasGraphResponse loaded = load(context);
            Map<String, Object> graph = mutableGraph(loaded.graph());

            List<Map<String, Object>> errors = new ArrayList<>();
            Map<String, String> refs = new LinkedHashMap<>();
            Map<String, List<String>> slotIdsByNode = new LinkedHashMap<>();
            PlanScope scope = new PlanScope(refs, slotIdsByNode, new LinkedHashSet<>(), new LinkedHashSet<>());
            List<String> nodeIds = new ArrayList<>();
            List<String> slotIds = new ArrayList<>();
            List<String> edgeIds = new ArrayList<>();
            List<Map<String, Object>> createdEdges = new ArrayList<>();
            Map<Integer, Map<String, Object>> createdByIndex = new LinkedHashMap<>();
            // 内联素材的去重账本：同一份素材（mediaId/url）只建一个节点，后续 ref 复用同一个 id
            Map<String, String> mediaNodeIds = new LinkedHashMap<>();
            Map<String, String> mediaNodeRefs = new LinkedHashMap<>();
            Set<String> failedMediaKeys = new LinkedHashSet<>();
            List<String> assetIds = new ArrayList<>();
            List<Map<String, Object>> importedAssets = new ArrayList<>();

            // 阶段 1：解析 ref → 真实 id，提前暴露未知 kind、ref 重复与非法 media（还不建节点）
            List<PlannedNode> planned = planNodes(nodeSpecs, errors);

            // 阶段 2：建节点。普通节点走节点规格；带 media 的节点复用 importMediaBatch 的同一条素材导入路径
            for (PlannedNode item : planned) {
                if (!item.creatable()) continue;
                if (item.media() != null) {
                    String mediaKey = inlineMediaKey(item.media());
                    String existingId = mediaKey == null ? null : mediaNodeIds.get(mediaKey);
                    if (existingId != null) {
                        // 同一份素材第二次出现：复用已建节点，不再新增节点/资产/卡片
                        if (!blank(item.ref())) refs.put(item.ref(), existingId);
                        continue;
                    }
                    if (mediaKey != null && failedMediaKeys.contains(mediaKey)) {
                        errors.add(planError(item.index(), item.ref(), "DEPENDENCY_FAILED",
                                "与 ref=" + mediaNodeRefs.get(mediaKey) + " 是同一份素材，导入已失败，本节点跳过"));
                        if (!blank(item.ref())) scope.failedRefs().add(item.ref());
                        continue;
                    }
                    try {
                        ImportedMedia imported = importMediaIntoGraph(
                                context, canvas, graph, inlineMediaInput(item));
                        String id = string(imported.node().get("id"));
                        nodeIds.add(id);
                        createdByIndex.put(item.index(), imported.node());
                        if (!blank(item.ref())) refs.put(item.ref(), id);
                        assetIds.add(imported.asset().getId());
                        importedAssets.add(assetMap(imported.asset()));
                        if (mediaKey != null) {
                            mediaNodeIds.put(mediaKey, id);
                            mediaNodeRefs.put(mediaKey, item.ref());
                        }
                    } catch (CanvasGraphCommandException error) {
                        errors.add(batchError(item.index(), item.ref(), error));
                        if (!blank(item.ref())) scope.failedRefs().add(item.ref());
                        if (!blank(item.id())) scope.failedIds().add(item.id());
                        if (mediaKey != null) failedMediaKeys.add(mediaKey);
                    } catch (RuntimeException error) {
                        // 素材不存在（AssetServiceException）、地址不可信（BusinessException）、文件服务失败等
                        // 一律转成批次错误项，让模型一轮改完，而不是整条命令直接失败
                        errors.add(planError(item.index(), item.ref(), "MEDIA_IMPORT_FAILED", mediaImportMessage(error)));
                        if (!blank(item.ref())) scope.failedRefs().add(item.ref());
                        if (mediaKey != null) failedMediaKeys.add(mediaKey);
                    }
                    continue;
                }
                try {
                    Map<String, Object> created = createNodeInternal(graph, item.spec());
                    String id = string(created.get("id"));
                    nodeIds.add(id);
                    createdByIndex.put(item.index(), created);
                    if (!blank(item.ref())) refs.put(item.ref(), id);
                } catch (CanvasGraphCommandException error) {
                    errors.add(batchError(item.index(), item.ref(), error));
                    if (!blank(item.ref())) scope.failedRefs().add(item.ref());
                    if (!blank(item.id())) scope.failedIds().add(item.id());
                }
            }

            // 阶段 3：结果槽（先建齐全部槽，refs 的 .result.<n> 才能解析）
            for (PlannedNode item : planned) {
                Map<String, Object> producer = createdByIndex.get(item.index());
                if (producer == null || item.spec().get("resultSlots") == null) continue;
                try {
                    ResultSlotBatch batch = createResultSlotsForNode(graph, producer, item.spec());
                    slotIds.addAll(batch.nodeIds());
                    slotIdsByNode.put(string(producer.get("id")), batch.nodeIds());
                    edgeIds.addAll(batch.edgeIds());
                    createdEdges.addAll(batch.edges());
                } catch (CanvasGraphCommandException error) {
                    errors.add(batchError(item.index(), item.ref(), error));
                }
            }

            // 阶段 4a：refs 声明式连线（端口与 portOrder 由端口表自动决定）
            for (PlannedNode item : planned) {
                Map<String, Object> targetNode = createdByIndex.get(item.index());
                if (targetNode == null) continue;
                for (String expression : strings(item.spec().get("refs"))) {
                    try {
                        Map<String, Object> spec = resolveRefEdge(graph, expression, targetNode, scope);
                        if (spec == null) continue; // 幂等去重：同一条边已存在
                        Map<String, Object> created = createEdgeInternal(graph, spec);
                        edgeIds.add(string(created.get("id")));
                        createdEdges.add(created);
                    } catch (CanvasGraphCommandException error) {
                        errors.add(batchError(item.index(), item.ref(), error));
                    }
                }
            }

            // 阶段 4b：显式边（与 refs 可并存；完全相同的边幂等去重不报错）
            for (int index = 0; index < edgeSpecs.size(); index++) {
                Map<String, Object> spec = edgeSpecs.get(index);
                try {
                    String existingId = existingEdgeId(graph,
                            string(spec.get("source")), string(spec.get("sourceHandle")),
                            string(spec.get("target")), string(spec.get("targetHandle")));
                    if (existingId != null) {
                        edgeIds.add(existingId);
                        continue;
                    }
                    Map<String, Object> created = createEdgeInternal(graph, spec);
                    edgeIds.add(string(created.get("id")));
                    createdEdges.add(created);
                } catch (CanvasGraphCommandException error) {
                    errors.add(batchError(index, null, error));
                }
            }

            // 汇总：false = 一条都不落库；true = 落库成功项并在返回里报 errors/skipped
            if (!errors.isEmpty() && !continueOnError) {
                throw new CanvasGraphCommandException("PLAN_INVALID",
                        "计划有 " + errors.size() + " 项未通过校验，整批未落库",
                        Map.of("errors", errors, "failedCount", errors.size()));
            }

            // 阶段 5：可选裁剪本批次建出、且没有任何下游连线的结果槽
            List<String> removedNodeIds = new ArrayList<>();
            List<String> removedEdgeIds = new ArrayList<>();
            if (Boolean.TRUE.equals(payload.get("pruneUnreferencedResultSlots")) && !slotIds.isEmpty()) {
                PruneOutcome pruned = pruneUnreferencedSlots(graph, slotIds);
                removedNodeIds.addAll(pruned.removedNodeIds());
                removedEdgeIds.addAll(pruned.removedEdgeIds());
                nodeIds.removeAll(pruned.removedNodeIds());
                slotIds.removeAll(pruned.removedNodeIds());
                edgeIds.removeAll(pruned.removedEdgeIds());
                createdEdges.removeIf(edge -> removedEdgeIds.contains(string(edge.get("id"))));
            }

            // 阶段 6：布局（同一事务、同一次 saveGraph，不额外增加 revision）
            Map<String, Object> layout = null;
            if (payload.get("layout") != null) {
                Map<String, Object> layoutArgs = mapOrEmpty(payload.get("layout"));
                if (strings(layoutArgs.get("nodeIds")).isEmpty()) {
                    List<String> scopeIds = new ArrayList<>(nodeIds);
                    scopeIds.addAll(slotIds);
                    if (!scopeIds.isEmpty()) layoutArgs.put("nodeIds", scopeIds);
                }
                if (!strings(layoutArgs.get("nodeIds")).isEmpty()) {
                    LayoutOutcome outcome = applyLayout(graph, layoutArgs);
                    layout = new LinkedHashMap<>();
                    layout.put("movedNodeIds", outcome.movedNodeIds());
                    layout.put("positions", outcome.positions());
                }
            }

            int revision = saveGraph(context, graph, loaded.revision());
            Map<String, Object> out = writeResult("apply_plan", context, revision, graph);
            out.put("nodeIds", nodeIds);
            out.put("edgeIds", edgeIds);
            out.put("resultSlotIds", slotIds);
            out.put("refs", refs);
            if (!assetIds.isEmpty()) out.put("assetIds", assetIds);
            if (layout != null) out.put("layout", layout);
            if (!removedNodeIds.isEmpty()) out.put("prunedNodeIds", removedNodeIds);

            // 增量通道：本次新增的节点（含结果槽）+ 本次新增的边 + 本次删除的槽
            List<Map<String, Object>> patchNodes = new ArrayList<>();
            nodeIds.stream().map(id -> findNode(graph, id)).filter(Objects::nonNull).forEach(patchNodes::add);
            slotIds.stream().map(id -> findNode(graph, id)).filter(Objects::nonNull).forEach(patchNodes::add);
            if (layout != null) {
                for (Object movedId : list(layout.get("movedNodeIds"))) {
                    Map<String, Object> moved = findNode(graph, string(movedId));
                    if (moved != null && patchNodes.stream()
                            .noneMatch(node -> Objects.equals(node.get("id"), moved.get("id")))) {
                        patchNodes.add(moved);
                    }
                }
            }
            out.put("patch", changePatch(patchNodes, createdEdges, removedNodeIds, removedEdgeIds));

            if (!errors.isEmpty()) {
                out.put("partial", true);
                out.put("errors", errors);
                out.put("skipped", errors.stream()
                        .map(item -> item.containsKey("ref") ? item.get("ref") : item.get("index"))
                        .toList());
            }
            if (detail) {
                out.put("nodes", nodeIds.stream().map(id -> findNode(graph, id)).filter(Objects::nonNull).toList());
                out.put("edges", edgeIds.stream().map(id -> findEdgeById(graph, id)).filter(Objects::nonNull).toList());
                if (!importedAssets.isEmpty()) out.put("assets", importedAssets);
            }
            return out;
        });
    }

    /**
     * 阶段 1：解析每个节点的 kind/id/ref；未知 kind、ref 重复与非法内联 media 也先在这里记下来，
     * 避免建到一半才报错（错误策略要求一次性汇总**全部**失败项）。
     *
     * <p>带 {@code media} 的节点是素材节点：kind（image/video）由素材本身决定，因此不在这里
     * 按 kind 生成 {@code <kind>-<uuid>} 形式的 id（未给 id 时交给 {@code importMediaIntoGraph} 生成
     * {@code media-<uuid>}）。
     */
    private static List<PlannedNode> planNodes(List<Map<String, Object>> nodeSpecs,
                                               List<Map<String, Object>> errors) {
        List<PlannedNode> planned = new ArrayList<>();
        Set<String> seenRefs = new LinkedHashSet<>();
        for (int index = 0; index < nodeSpecs.size(); index++) {
            Map<String, Object> spec = new LinkedHashMap<>(mapOrEmpty(nodeSpecs.get(index)));
            String ref = string(spec.get("ref"));
            Map<String, Object> media = map(spec.get("media"));
            String explicitId = string(spec.get("id"));
            if (!blank(ref) && !seenRefs.add(ref)) {
                errors.add(planError(index, ref, "DUPLICATE_REF", "ref 重复: " + ref));
                planned.add(new PlannedNode(index, ref, explicitId, nodeKindOf(spec), spec, false, media));
                continue;
            }
            if (spec.get("media") != null && media == null) {
                errors.add(planError(index, ref, "INVALID_ARGUMENTS",
                        "media 必须是对象，如 {mediaId:\"m-1\"} 或 {url:\"/api/files/images/a.png\"}"));
                planned.add(new PlannedNode(index, ref, explicitId, nodeKindOf(spec), spec, false, null));
                continue;
            }
            if (media != null) {
                String invalid = inlineMediaError(media, spec);
                if (invalid != null) {
                    errors.add(planError(index, ref, "INVALID_ARGUMENTS", invalid));
                    planned.add(new PlannedNode(index, ref, explicitId, "media", spec, false, media));
                    continue;
                }
                planned.add(new PlannedNode(index, ref, blank(explicitId) ? "" : explicitId,
                        "media", spec, true, media));
                continue;
            }
            String kind = nodeKindOf(spec);
            if (blank(explicitId)) {
                if (blank(ref)) {
                    // 无 ref 无 id：交给 createNodeInternal 生成 kind-uuid
                    planned.add(new PlannedNode(index, null, "", kind, spec, true, null));
                    continue;
                }
                explicitId = kind + "-" + UUID.randomUUID();
                spec.put("id", explicitId);
            }
            planned.add(new PlannedNode(index, ref, explicitId, kind, spec, true, null));
        }
        return planned;
    }

    /**
     * 内联素材的静态校验：mediaId 与 url 二选一，素材节点不能声明 resultSlots、也不能指定非媒体 type。
     *
     * @return 错误消息；合法时返回 {@code null}
     */
    private static String inlineMediaError(Map<String, Object> media, Map<String, Object> spec) {
        if (spec.get("resultSlots") != null) {
            return "带 media 的素材节点不能声明 resultSlots：素材节点不是生产节点，没有图片/视频输出";
        }
        String kind = nodeKindOf(spec);
        if (!blank(kind) && !"image".equals(kind) && !"video".equals(kind)) {
            return "带 media 的节点不能指定 type=" + kind + "：素材节点的 image/video 由素材本身决定";
        }
        String mediaId = string(media.get("mediaId"));
        String url = string(media.get("url"));
        if (blank(mediaId) && blank(url)) {
            return "media 必须提供 mediaId 或 url（二选一）";
        }
        if (!blank(mediaId) && !blank(url)) {
            return "media 的 mediaId 与 url 只能给一个（二选一）";
        }
        return null;
    }

    /** 内联素材导入失败的统一错误文案（下游异常不保证有 message）。 */
    private static String mediaImportMessage(RuntimeException error) {
        String message = error.getMessage();
        return blank(message)
                ? "素材导入失败：" + error.getClass().getSimpleName()
                : "素材导入失败：" + message;
    }

    /**
     * 内联素材的去重键：mediaId 优先，退化为去掉签名 query 的 url。
     *
     * <p>同一键在同一个 plan 里只建一个素材节点，其余节点通过各自的 ref 复用同一个节点 id。
     */
    private static String inlineMediaKey(Map<String, Object> media) {
        String mediaId = string(media.get("mediaId"));
        if (!blank(mediaId)) return "mediaId:" + mediaId.trim();
        String url = string(media.get("url"));
        return blank(url) ? null : "url:" + stripQuery(url.trim());
    }

    /**
     * 把 plan 节点上的内联 {@code media} 与节点规格的排布字段合成 {@code importMediaIntoGraph} 的入参：
     * media 自己的字段优先，节点规格的 position/width/height/data.label 作为补充。
     */
    private static Map<String, Object> inlineMediaInput(PlannedNode item) {
        Map<String, Object> input = new LinkedHashMap<>(item.media());
        Map<String, Object> spec = item.spec();
        if (!blank(item.id())) input.putIfAbsent("nodeId", item.id());
        for (String key : List.of("position", "width", "height")) {
            if (input.get(key) == null && spec.get(key) != null) input.put(key, spec.get(key));
        }
        Map<String, Object> specData = mapOrEmpty(spec.get("data"));
        if (blank(string(input.get("label"))) && !blank(string(specData.get("label")))) {
            input.put("label", specData.get("label"));
        }
        Map<String, Object> data = new LinkedHashMap<>(specData);
        data.putAll(mapOrEmpty(input.get("data")));
        if (!data.isEmpty()) input.put("data", data);
        return input;
    }

    /**
     * 解析一条 refs 表达式为可提交的连线参数。
     *
     * <p>{@code "<ref>"} = 引用该节点输出；{@code "<ref>.result.<n>"} = 引用该节点第 n 个结果槽输出。
     * 端口由端口表自动选择；ordered 输入口（如 reference）按 refs 数组顺序拿到 0,1,2… 的 portOrder。
     *
     * @return 连线参数；完全相同的连线已存在时返回 {@code null}（幂等去重）
     */
    private static Map<String, Object> resolveRefEdge(Map<String, Object> graph,
                                                      String expression,
                                                      Map<String, Object> targetNode,
                                                      PlanScope scope) {
        RefExpression ref = parseRefExpression(expression);
        if (ref == null) {
            throw new CanvasGraphCommandException("INVALID_REF", "refs 写法非法: " + expression);
        }
        if (scope.failedRefs().contains(ref.target())) {
            throw new CanvasGraphCommandException("DEPENDENCY_FAILED",
                    "引用的 " + ref.target() + " 未成功创建，依赖它的连线已跳过");
        }
        String sourceNodeId = scope.resolveNodeId(graph, ref.target());
        if (blank(sourceNodeId)) {
            throw new CanvasGraphCommandException("UNKNOWN_REF",
                    "refs 引用目标不存在: " + ref.target() + "（既不是本批次 ref，也不是画布上的节点 id）");
        }
        if (scope.failedIds().contains(sourceNodeId)) {
            throw new CanvasGraphCommandException("DEPENDENCY_FAILED",
                    "引用的节点 " + sourceNodeId + " 未成功创建，依赖它的连线已跳过");
        }
        String targetId = string(targetNode.get("id"));
        String sourceId = sourceNodeId;
        if (ref.resultIndex() != null) {
            List<String> slots = scope.slotIdsByNode().get(sourceNodeId);
            if (slots == null || slots.isEmpty()) {
                throw new CanvasGraphCommandException("RESULT_SLOT_REQUIRED",
                        ref.target() + " 没有结果槽：请在该节点声明 resultSlots，或把 refs 改回 \"" + ref.target() + "\"");
            }
            if (ref.resultIndex() >= slots.size()) {
                throw new CanvasGraphCommandException("RESULT_SLOT_NOT_FOUND",
                        ref.target() + " 只有 " + slots.size() + " 个结果槽，取不到第 " + ref.resultIndex() + " 个");
            }
            sourceId = slots.get(ref.resultIndex());
        }
        Map<String, Object> sourceNode = findNode(graph, sourceId);
        if (sourceNode == null) {
            throw new CanvasGraphCommandException("NODE_NOT_FOUND", "refs 来源节点不存在: " + sourceId);
        }
        PortPair pair = selectPorts(graph, sourceNode, targetNode, scope.preferredPort(targetId));
        scope.preferPort(targetId, pair.input().id());
        if (existingEdgeId(graph, sourceId, pair.output().id(), targetId, pair.input().id()) != null) {
            return null;
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("source", sourceId);
        spec.put("sourceHandle", pair.output().id());
        spec.put("target", targetId);
        spec.put("targetHandle", pair.input().id());
        return spec;
    }

    /**
     * refs 的端口自动选择：在「输入口接受来源 kind」的 (输出口, 输入口) 组合里依次优先
     * ①还有余量 ②本节点前面 refs 已用过的输入口 ③必填输入口，最后按声明顺序取第一个。
     *
     * <p>「还有余量」优先保证 imageToImage 这类「必填源图 + 可选参考图」的节点能把第 2 个
     * 引用溢出到第二个端口，而不是撞 PORT_LIMIT_EXCEEDED。
     */
    private static PortPair selectPorts(Map<String, Object> graph,
                                        Map<String, Object> sourceNode,
                                        Map<String, Object> targetNode,
                                        String preferredInputId) {
        String sourceKind = kindOf(sourceNode);
        String targetId = string(targetNode.get("id"));
        CanvasNodeRegistry.NodeType sourceType = CanvasNodeRegistry.require(sourceKind);
        CanvasNodeRegistry.NodeType targetType = CanvasNodeRegistry.require(kindOf(targetNode));
        List<PortPair> mediaMatched = new ArrayList<>();
        List<PortPair> kindMatched = new ArrayList<>();
        for (CanvasNodeRegistry.Port output : sourceType.outputs()) {
            for (CanvasNodeRegistry.Port input : targetType.inputs()) {
                if (!input.acceptsKinds().contains(sourceKind)) continue;
                PortPair pair = new PortPair(output, input);
                kindMatched.add(pair);
                if (Objects.equals(output.media(), input.media())) mediaMatched.add(pair);
            }
        }
        List<PortPair> candidates = mediaMatched.isEmpty() ? kindMatched : mediaMatched;
        if (candidates.isEmpty()) {
            throw new CanvasGraphCommandException("PORT_KIND_MISMATCH",
                    targetType.title() + " 不接受 " + sourceType.title() + " 的输出" + resultSlotHint(sourceType));
        }
        return candidates.stream()
                .min(Comparator
                        .comparingInt((PortPair pair) -> hasCapacity(graph, targetId, pair.input()) ? 0 : 1)
                        .thenComparingInt(pair -> pair.input().id().equals(preferredInputId) ? 0 : 1)
                        .thenComparingInt(pair -> pair.input().required() ? 0 : 1))
                .orElse(candidates.getFirst());
    }

    /** 生产节点的输出不能直连下游功能节点：提示模型改用 .result.<n> 走结果槽。 */
    private static String resultSlotHint(CanvasNodeRegistry.NodeType sourceType) {
        boolean producesMedia = sourceType.outputs().stream()
                .anyMatch(port -> "image".equals(port.media()) || "video".equals(port.media()));
        return producesMedia
                ? "；生成节点必须先接结果槽，请改用 refs 的 \"" + sourceType.kind() + ".result.0\" 写法"
                : "";
    }

    private static boolean hasCapacity(Map<String, Object> graph, String targetId, CanvasNodeRegistry.Port port) {
        if (port.max() < 0) return true;
        long bound = listOfMaps(graph.get("edges")).stream()
                .filter(edge -> Objects.equals(targetId, string(edge.get("target")))
                        && Objects.equals(port.id(), string(edge.get("targetHandle"))))
                .count();
        return bound < port.max();
    }

    /** 解析 refs 表达式：{@code <ref>} 或 {@code <ref>.result.<n>}。 */
    private static RefExpression parseRefExpression(String expression) {
        if (blank(expression)) return null;
        Matcher matcher = RESULT_REF.matcher(expression.trim());
        if (matcher.matches()) {
            return new RefExpression(matcher.group(1), integer(matcher.group(2), 0));
        }
        return new RefExpression(expression.trim(), null);
    }

    /** 已存在的同源同目标连线 id；显式给了端口时还要端口一致。用于批量/整图提交的幂等去重。 */
    private static String existingEdgeId(Map<String, Object> graph,
                                         String sourceId,
                                         String sourceHandle,
                                         String targetId,
                                         String targetHandle) {
        if (blank(sourceId) || blank(targetId)) return null;
        for (Map<String, Object> edge : listOfMaps(graph.get("edges"))) {
            if (!Objects.equals(sourceId, string(edge.get("source")))) continue;
            if (!Objects.equals(targetId, string(edge.get("target")))) continue;
            if (!blank(sourceHandle) && !Objects.equals(sourceHandle, string(edge.get("sourceHandle")))) continue;
            if (!blank(targetHandle) && !Objects.equals(targetHandle, string(edge.get("targetHandle")))) continue;
            return string(edge.get("id"));
        }
        return null;
    }

    private static Map<String, Object> findEdgeById(Map<String, Object> graph, String edgeId) {
        return listOfMaps(graph.get("edges")).stream()
                .filter(edge -> Objects.equals(edgeId, string(edge.get("id"))))
                .findFirst()
                .orElse(null);
    }

    /** 删掉本批次建出、且没有任何下游连线的结果槽（连同它的入边），返回被删除的 id 供 patch 使用。 */
    private static PruneOutcome pruneUnreferencedSlots(Map<String, Object> graph, List<String> slotIds) {
        List<Map<String, Object>> nodes = listOfMaps(graph.get("nodes"));
        List<Map<String, Object>> edges = listOfMaps(graph.get("edges"));
        List<String> removedNodeIds = new ArrayList<>();
        List<String> removedEdgeIds = new ArrayList<>();
        for (String slotId : slotIds) {
            boolean hasDownstream = edges.stream()
                    .anyMatch(edge -> Objects.equals(slotId, string(edge.get("source"))));
            if (hasDownstream) continue;
            nodes.removeIf(node -> Objects.equals(slotId, string(node.get("id"))));
            Iterator<Map<String, Object>> cursor = edges.iterator();
            while (cursor.hasNext()) {
                Map<String, Object> edge = cursor.next();
                if (Objects.equals(slotId, string(edge.get("source")))
                        || Objects.equals(slotId, string(edge.get("target")))) {
                    removedEdgeIds.add(string(edge.get("id")));
                    cursor.remove();
                }
            }
            removedNodeIds.add(slotId);
        }
        graph.put("nodes", nodes);
        graph.put("edges", edges);
        return new PruneOutcome(removedNodeIds, removedEdgeIds);
    }

    /**
     * 结果槽核心逻辑：{@code create_result_slots} 与节点规格里的 {@code resultSlots} 共用。
     *
     * <p>「结果 N」编号由 {@link #nextResultSlotIndex} 在整张画布内递增，同一批多个节点各自建槽
     * 也会连续递增（每次调用都读当前图）。
     */
    private ResultSlotBatch createResultSlotsInternal(Map<String, Object> graph,
                                                      String producerNodeId,
                                                      String kind,
                                                      int count,
                                                      Map<String, Object> position,
                                                      Object gapYValue) {
        if (blank(producerNodeId)) {
            throw new CanvasGraphCommandException("INVALID_ARGUMENTS", "producerNodeId 不能为空");
        }
        if (!"image".equals(kind) && !"video".equals(kind)) {
            throw new CanvasGraphCommandException("INVALID_ARGUMENTS", "kind 必须是 image/video");
        }
        if (count < 1 || count > MAX_RESULT_SLOTS) {
            throw new CanvasGraphCommandException("INVALID_ARGUMENTS", "count 必须是 1~" + MAX_RESULT_SLOTS);
        }
        Map<String, Object> producer = findNode(graph, producerNodeId);
        if (producer == null) {
            throw new CanvasGraphCommandException("NODE_NOT_FOUND", "生产节点不存在: " + producerNodeId);
        }
        CanvasNodeRegistry.NodeType producerType = CanvasNodeRegistry.require(kindOf(producer));
        CanvasNodeRegistry.Port output = producerType.outputs().stream()
                .filter(port -> kind.equals(port.media()))
                .findFirst()
                .orElseThrow(() -> new CanvasGraphCommandException(
                        "PORT_NOT_FOUND", "生产节点没有 " + kind + " 输出"));
        CanvasNodeRegistry.NodeType slotType = CanvasNodeRegistry.require(kind);
        double producerX = number(mapOrEmpty(producer.get("position")).get("x"), 0);
        double producerY = number(mapOrEmpty(producer.get("position")).get("y"), 0);
        double producerWidth = number(producer.get("width"), producerType.width());
        double gapY = number(gapYValue, slotType.height() + 40);
        double originX = position == null ? producerX + producerWidth + 160 : number(position.get("x"), producerX);
        double originY = position == null ? producerY : number(position.get("y"), producerY);

        List<String> nodeIds = new ArrayList<>();
        List<String> edgeIds = new ArrayList<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        int startIndex = nextResultSlotIndex(graph, kind);
        for (int index = 0; index < count; index++) {
            Map<String, Object> slot = createNodeInternal(graph, Map.of(
                    "type", kind,
                    "position", Map.of("x", originX, "y", originY + index * gapY),
                    "data", Map.of(
                            "label", "结果 " + (startIndex + index),
                            "producerNodeId", producerNodeId,
                            "status", "idle")));
            nodeIds.add(string(slot.get("id")));
            nodes.add(slot);
            Map<String, Object> edge = createEdgeInternal(graph, Map.of(
                    "source", producerNodeId,
                    "sourceHandle", output.id(),
                    "target", slot.get("id"),
                    "targetHandle", "origin",
                    "data", Map.of("resultSlot", true, "resultSlotOrder", index)));
            edgeIds.add(string(edge.get("id")));
            edges.add(edge);
        }
        return new ResultSlotBatch(nodeIds, edgeIds, nodes, edges);
    }

    /**
     * 节点规格里的 {@code resultSlots:{kind?,count?,position?,gapY?}} → 立刻建槽并连回该节点。
     *
     * <p>kind 省略时按该节点唯一的图片/视频输出推断（图片/视频媒体占位节点因此天然得到
     * image/video）；非生产节点（没有该媒体输出端口）报 INVALID_ARGUMENTS。
     */
    private ResultSlotBatch createResultSlotsForNode(Map<String, Object> graph,
                                                     Map<String, Object> producer,
                                                     Map<String, Object> spec) {
        Object raw = spec == null ? null : spec.get("resultSlots");
        if (raw == null) return ResultSlotBatch.empty();
        Map<String, Object> slotSpec = map(raw);
        if (slotSpec == null) {
            throw new CanvasGraphCommandException("INVALID_ARGUMENTS", "resultSlots 必须是对象");
        }
        CanvasNodeRegistry.NodeType type = CanvasNodeRegistry.require(kindOf(producer));
        String requested = normalizeMediaKind(string(slotSpec.get("kind")));
        String kind = blank(requested) ? defaultResultSlotKind(type) : requested;
        if (blank(kind)) {
            throw new CanvasGraphCommandException("INVALID_ARGUMENTS",
                    type.title() + " 同时有图片与视频输出，resultSlots.kind 必须显式指定 image 或 video");
        }
        if (type.outputs().stream().noneMatch(port -> kind.equals(port.media()))) {
            throw new CanvasGraphCommandException("INVALID_ARGUMENTS",
                    type.title() + " 没有 " + kind + " 输出，不能声明 " + kind + " 结果槽");
        }
        return createResultSlotsInternal(graph, string(producer.get("id")), kind,
                integer(slotSpec.get("count"), 1), map(slotSpec.get("position")), slotSpec.get("gapY"));
    }

    /** 节点唯一的图片/视频输出媒体；同时有两者（如 shot）时返回空串，要求显式 kind。 */
    private static String defaultResultSlotKind(CanvasNodeRegistry.NodeType type) {
        Set<String> media = new LinkedHashSet<>();
        for (CanvasNodeRegistry.Port port : type.outputs()) {
            if ("image".equals(port.media()) || "video".equals(port.media())) media.add(port.media());
        }
        return media.size() == 1 ? media.iterator().next() : "";
    }

    /**
     * 本次变更明细：WS3 前端据此增量应用画布（不再整图重载），MoonAgent 会把它放进
     * {@code canvas_run_updated.payload.patch}。
     */
    private static Map<String, Object> changePatch(List<Map<String, Object>> nodes,
                                                   List<Map<String, Object>> edges,
                                                   List<String> removedNodeIds,
                                                   List<String> removedEdgeIds) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", nodes == null ? List.of() : nodes);
        out.put("edges", edges == null ? List.of() : edges);
        out.put("removedNodeIds", removedNodeIds == null ? List.of() : removedNodeIds);
        out.put("removedEdgeIds", removedEdgeIds == null ? List.of() : removedEdgeIds);
        return out;
    }

    /** 读工具的紧凑节点视图：只留渲染与判断所需字段，不含长文本 prompt 与带签名的 assetUrl。 */
    private static Map<String, Object> compactNode(Map<String, Object> node) {
        Map<String, Object> data = mapOrEmpty(node.get("data"));
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("id", node.get("id"));
        compact.put("type", node.get("type"));
        for (String key : COMPACT_NODE_DATA_KEYS) {
            Object value = data.get(key);
            if (value == null) continue;
            String text = string(value);
            if (blank(text)) continue;
            compact.put(key, text.length() > 120 ? text.substring(0, 120) + "…" : text);
        }
        String assetId = string(data.get("assetId"));
        if (!blank(assetId)) compact.put("assetId", assetId);
        compact.put("position", mapOrEmpty(node.get("position")));
        compact.put("width", node.get("width"));
        compact.put("height", node.get("height"));
        return compact;
    }

    /** 读工具的紧凑连线视图：只留端点、端口与 resultSlot/portOrder 语义字段。 */
    private static Map<String, Object> compactEdge(Map<String, Object> edge) {
        Map<String, Object> data = mapOrEmpty(edge.get("data"));
        Map<String, Object> compactData = new LinkedHashMap<>();
        for (String key : COMPACT_EDGE_DATA_KEYS) {
            if (data.containsKey(key)) compactData.put(key, data.get(key));
        }
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("id", edge.get("id"));
        compact.put("source", edge.get("source"));
        compact.put("sourceHandle", edge.get("sourceHandle"));
        compact.put("target", edge.get("target"));
        compact.put("targetHandle", edge.get("targetHandle"));
        if (!compactData.isEmpty()) compact.put("data", compactData);
        return compact;
    }

    /** 批量命令的失败项：索引（或 ref）+ 稳定错误码 + 可读消息（带上原始 details）。 */
    private static Map<String, Object> batchError(int index, String ref, CanvasGraphCommandException error) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("index", index);
        if (!blank(ref)) item.put("ref", ref);
        item.put("code", error.code());
        item.put("message", error.getMessage());
        if (!error.details().isEmpty()) item.put("details", error.details());
        return item;
    }

    private static Map<String, Object> planError(int index, String ref, String code, String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("index", index);
        if (!blank(ref)) item.put("ref", ref);
        item.put("code", code);
        item.put("message", message);
        return item;
    }

    /** 节点 kind 解析优先级：type → kind → data.kind，再过 normalizeAlias 别名归一。 */
    private static String nodeKindOf(Map<String, Object> spec) {
        return CanvasNodeRegistry.normalizeAlias(firstNonBlank(
                string(spec.get("type")),
                string(spec.get("kind")),
                string(mapOrEmpty(spec.get("data")).get("kind"))));
    }

    private static int pageSize(Integer limit) {
        return limit == null || limit <= 0 ? DEFAULT_PAGE_SIZE : limit;
    }

    private static int pageOffset(Integer offset) {
        return Math.max(0, offset == null ? 0 : offset);
    }

    /** list_nodes / list_edges 的分页：limit 缺省 100 条。 */
    private static List<Map<String, Object>> page(List<Map<String, Object>> items, Integer limit, Integer offset) {
        int from = pageOffset(offset);
        if (from >= items.size()) return new ArrayList<>();
        int to = Math.min(items.size(), from + pageSize(limit));
        return new ArrayList<>(items.subList(from, to));
    }

    /** get_state 的分页：limit 为空或非正数时不分页，保持既有语义。 */
    private static List<Map<String, Object>> slice(List<Map<String, Object>> items, Integer limit, Integer offset) {
        if (limit == null || limit <= 0) return new ArrayList<>(items);
        return page(items, limit, offset);
    }

    private Map<String, Object> idempotent(
            CanvasGraphCommandContext context,
            String commandName,
            java.util.function.Supplier<Map<String, Object>> action
    ) {
        if (blank(context.operationKey())) {
            throw new CanvasGraphCommandException("OPERATION_KEY_REQUIRED", "operationKey 不能为空");
        }
        CanvasGraphOperation existing = operationMapper.selectById(context.operationKey());
        if (existing != null) return replay(context, commandName, existing);

        int claimed = operationMapper.claim(
                context.operationKey(),
                context.canvasId(),
                context.userId(),
                context.projectId(),
                context.runId(),
                commandName);
        if (claimed == 0) {
            CanvasGraphOperation concurrent = operationMapper.selectById(context.operationKey());
            if (concurrent == null) {
                throw new CanvasGraphCommandException("OPERATION_CONFLICT", "幂等操作竞争失败，请重试");
            }
            return replay(context, commandName, concurrent);
        }
        Map<String, Object> result = action.get();
        operationMapper.complete(
                context.operationKey(),
                JsonUtil.toJson(result),
                context.canvasId(),
                context.userId());
        return result;
    }

    private Map<String, Object> replay(
            CanvasGraphCommandContext context,
            String commandName,
            CanvasGraphOperation existing
    ) {
        if (!Objects.equals(existing.getCanvasId(), context.canvasId())
                || !Objects.equals(existing.getUserId(), context.userId())) {
            throw new CanvasGraphCommandException("OPERATION_SCOPE_MISMATCH",
                    "operationKey 已用于其他画布或用户");
        }
        if (!Objects.equals(existing.getCommandName(), commandName)) {
            throw new CanvasGraphCommandException("OPERATION_KEY_CONFLICT",
                    "operationKey 已用于其他命令: " + existing.getCommandName());
        }
        if (!"completed".equals(existing.getStatus())) {
            throw new CanvasGraphCommandException("OPERATION_IN_PROGRESS", "同一幂等操作正在执行，请稍后重试");
        }
        Map<String, Object> result = new LinkedHashMap<>(JsonUtil.parse(existing.getResultJson()));
        result.put("idempotentReplay", true);
        return result;
    }

    private MorrowCanvas requireCanvas(CanvasGraphCommandContext context) {
        if (context == null || blank(context.userId()) || blank(context.canvasId())) {
            throw new CanvasGraphCommandException("INVALID_ARGUMENTS", "userId 和 canvasId 不能为空");
        }
        MorrowCanvas canvas = canvasService.get(context.userId(), context.canvasId());
        if (!blank(context.projectId())
                && !Objects.equals(context.projectId(), canvas.getProjectId())) {
            throw new CanvasGraphCommandException("CANVAS_PROJECT_MISMATCH",
                    "画布不属于指定项目", Map.of(
                            "canvasId", canvas.getId(),
                            "expectedProjectId", context.projectId(),
                            "actualProjectId", canvas.getProjectId()));
        }
        return canvas;
    }

    private CanvasGraphResponse load(CanvasGraphCommandContext context) {
        return graphService.load(context.userId(), context.canvasId());
    }

    private int saveGraph(CanvasGraphCommandContext context, Map<String, Object> graph, int baseRevision) {
        try {
            CanvasGraphResponse saved = graphService.save(context.userId(), context.canvasId(), graph, baseRevision);
            return saved.revision();
        } catch (BusinessException e) {
            if (e.getCode() == 40901) {
                throw new CanvasGraphCommandException("CANVAS_REVISION_CONFLICT", e.getMessage());
            }
            throw e;
        }
    }

    private Map<String, Object> createNodeInternal(Map<String, Object> graph, Map<String, Object> spec) {
        if (spec == null) {
            throw new CanvasGraphCommandException("INVALID_ARGUMENTS", "节点参数不能为空");
        }
        String kind = nodeKindOf(spec);
        CanvasNodeRegistry.NodeType definition = CanvasNodeRegistry.require(kind);
        List<Map<String, Object>> nodes = listOfMaps(graph.get("nodes"));
        String id = firstNonBlank(string(spec.get("id")), kind + "-" + UUID.randomUUID());
        if (findNode(graph, id) != null) {
            throw new CanvasGraphCommandException("NODE_ALREADY_EXISTS", "节点 ID 已存在: " + id);
        }
        Map<String, Object> position = map(spec.get("position"));
        if (position == null) position = nextFreePosition(nodes);
        Map<String, Object> data = mutableMap(spec.get("data"));
        // 顶层扁平写法（label/mode/prompt/status）等价于 data 里的同名字段，data 优先
        for (String key : NODE_DATA_FLAT_KEYS) {
            Object value = spec.get(key);
            if (value != null && !data.containsKey(key)) data.put(key, value);
        }
        data.put("kind", kind);
        data.putIfAbsent("label", definition.title());
        data.putIfAbsent("prompt", "");
        data.putIfAbsent("status", "idle");
        data.putIfAbsent("accent", definition.accent());
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", kind);
        node.put("position", Map.of(
                "x", number(position.get("x"), 0),
                "y", number(position.get("y"), 0)));
        node.put("width", number(spec.get("width"), definition.width()));
        node.put("height", number(spec.get("height"), definition.height()));
        node.put("data", data);
        nodes.add(node);
        graph.put("nodes", nodes);
        return node;
    }

    private Map<String, Object> createEdgeInternal(Map<String, Object> graph, Map<String, Object> spec) {
        if (spec == null) {
            throw new CanvasGraphCommandException("INVALID_ARGUMENTS", "连线参数不能为空");
        }
        String sourceId = string(spec.get("source"));
        String targetId = string(spec.get("target"));
        if (blank(sourceId) || blank(targetId)) {
            throw new CanvasGraphCommandException("INVALID_ARGUMENTS", "source/target 不能为空");
        }
        if (sourceId.equals(targetId)) {
            throw new CanvasGraphCommandException("SELF_LOOP", "节点不能连接自身");
        }
        Map<String, Object> sourceNode = findNode(graph, sourceId);
        Map<String, Object> targetNode = findNode(graph, targetId);
        if (sourceNode == null || targetNode == null) {
            throw new CanvasGraphCommandException("NODE_NOT_FOUND", "源节点或目标节点不存在");
        }
        String sourceKind = kindOf(sourceNode);
        String targetKind = kindOf(targetNode);
        CanvasNodeRegistry.NodeType sourceType = CanvasNodeRegistry.require(sourceKind);
        CanvasNodeRegistry.NodeType targetType = CanvasNodeRegistry.require(targetKind);
        String sourceHandle = string(spec.get("sourceHandle"));
        CanvasNodeRegistry.Port output = sourceHandle == null
                ? sourceType.outputs().stream().findFirst().orElse(null)
                : sourceType.outputs().stream().filter(port -> port.id().equals(sourceHandle)).findFirst().orElse(null);
        if (output == null) {
            throw new CanvasGraphCommandException("PORT_NOT_FOUND",
                    "源节点没有输出端口: " + sourceHandle);
        }
        String targetHandle = string(spec.get("targetHandle"));
        CanvasNodeRegistry.Port input = targetHandle == null
                ? targetType.inputs().stream()
                    .filter(port -> port.acceptsKinds().contains(sourceKind))
                    .findFirst()
                    .orElse(targetType.inputs().stream().findFirst().orElse(null))
                : targetType.inputs().stream().filter(port -> port.id().equals(targetHandle)).findFirst().orElse(null);
        if (input == null) {
            throw new CanvasGraphCommandException("PORT_NOT_FOUND",
                    "目标节点没有输入端口: " + targetHandle);
        }
        if (!input.acceptsKinds().contains(sourceKind)) {
            throw new CanvasGraphCommandException("PORT_KIND_MISMATCH",
                    input.label() + " 不接受 " + sourceKind);
        }
        if (!Objects.equals(output.media(), input.media())) {
            throw new CanvasGraphCommandException("PORT_MEDIA_MISMATCH",
                    "输出端口与输入端口媒体类型不一致");
        }
        List<Map<String, Object>> edges = listOfMaps(graph.get("edges"));
        boolean duplicate = edges.stream().anyMatch(edge ->
                Objects.equals(edge.get("source"), sourceId)
                        && Objects.equals(edge.get("target"), targetId)
                        && Objects.equals(edge.get("sourceHandle"), output.id())
                        && Objects.equals(edge.get("targetHandle"), input.id()));
        if (duplicate) {
            throw new CanvasGraphCommandException("DUPLICATE_EDGE", "该连线已存在");
        }
        long bound = edges.stream()
                .filter(edge -> Objects.equals(edge.get("target"), targetId)
                        && Objects.equals(edge.get("targetHandle"), input.id()))
                .count();
        if (input.max() >= 0 && bound >= input.max()) {
            throw new CanvasGraphCommandException("PORT_LIMIT_EXCEEDED",
                    input.label() + " 最多连接 " + input.max() + " 条");
        }
        Map<String, Object> edgeData = mutableMap(spec.get("data"));
        if (input.ordered()) {
            Integer order = integerObject(edgeData.get("portOrder"));
            if (order == null) order = integerObject(spec.get("portOrder"));
            if (order == null) order = nextPortOrder(edges, targetId, input.id());
            if (order < 0) {
                throw new CanvasGraphCommandException("PORT_ORDER_REQUIRED", input.label() + " 需要非负 portOrder");
            }
            int candidate = order;
            boolean used = edges.stream().anyMatch(edge ->
                    Objects.equals(edge.get("target"), targetId)
                            && Objects.equals(edge.get("targetHandle"), input.id())
                            && candidate == integer(mapOrEmpty(edge.get("data")).get("portOrder"), -1));
            if (used) {
                throw new CanvasGraphCommandException("PORT_ORDER_CONFLICT",
                        input.label() + " 的顺序 " + order + " 已被占用");
            }
            edgeData.put("portOrder", order);
        }
        if (hasPath(edges, targetId, sourceId)) {
            throw new CanvasGraphCommandException("CYCLE", "该连线会形成循环依赖");
        }
        String edgeId = firstNonBlank(string(spec.get("id")), "edge-" + UUID.randomUUID());
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", edgeId);
        edge.put("source", sourceId);
        edge.put("sourceHandle", output.id());
        edge.put("target", targetId);
        edge.put("targetHandle", input.id());
        edge.put("type", "canvas");
        edge.put("data", edgeData);
        edges.add(edge);
        graph.put("edges", edges);
        return edge;
    }

    private CanvasAsset resolveMediaAsset(
            CanvasGraphCommandContext context,
            MorrowCanvas canvas,
            Map<String, Object> media,
            String producerNodeId
    ) {
        String assetId = string(media.get("assetId"));
        if (!blank(assetId)) {
            CanvasAsset asset = assetService.getOwned(context.userId(), assetId);
            if (!Objects.equals(asset.getCanvasId(), context.canvasId())) {
                throw new CanvasGraphCommandException("ASSET_CANVAS_MISMATCH",
                        "assetId 不属于当前画布", Map.of("assetId", assetId));
            }
            return asset;
        }
        String mediaId = string(media.get("mediaId"));
        if (!blank(mediaId)) {
            return libraryService.importMedia(context.userId(), context.canvasId(), mediaId, producerNodeId);
        }
        String libraryId = string(media.get("libraryAssetId"));
        if (!blank(libraryId)) {
            return libraryService.importAsset(context.userId(), context.canvasId(), libraryId, producerNodeId);
        }
        String url = string(media.get("url"));
        if (blank(url)) {
            throw new CanvasGraphCommandException("MEDIA_URL_REQUIRED", "assetId/mediaId/libraryAssetId/url 至少提供一个");
        }
        String mediaIdFromUrl = libraryMediaId(url);
        if (!blank(mediaIdFromUrl)) {
            return libraryService.importMedia(
                    context.userId(), context.canvasId(), mediaIdFromUrl, producerNodeId);
        }
        String kind = normalizeMediaKind(firstNonBlank(
                string(media.get("kind")),
                detectMediaKind(string(media.get("mimeType")), string(media.get("name")), url)));
        String objectUrl;
        if (url.startsWith(FILE_URL_PREFIX)) {
            objectUrl = stripQuery(url);
        } else {
            String source = url.startsWith("http://") || url.startsWith("https://")
                    ? url
                    : internalStoryboardUrl + (url.startsWith("/") ? url : "/" + url);
            objectUrl = "video".equals(kind)
                    ? fileStorageService.saveVideo(source)
                    : fileStorageService.saveImage(source);
        }
        String objectKey = objectUrl.substring(FILE_URL_PREFIX.length());
        CanvasAsset existing = findAssetByObject(context.canvasId(), objectUrl);
        if (existing != null) return existing;
        CanvasAsset asset = new CanvasAsset();
        asset.setId(UUID.randomUUID().toString());
        asset.setCanvasId(context.canvasId());
        asset.setProjectId(canvas.getProjectId());
        asset.setUserId(context.userId());
        asset.setType(kind);
        asset.setMimeType(firstNonBlank(
                string(media.get("mimeType")),
                FileStorageService.contentType(firstNonBlank(string(media.get("name")), objectUrl))));
        asset.setObjectUrl(objectUrl);
        asset.setObjectKey(objectKey);
        asset.setProducerNodeId(producerNodeId);
        asset.setStatus("ready");
        asset.setLibrarySyncState("pending");
        try {
            assetMapper.insert(asset);
        } catch (DuplicateKeyException duplicate) {
            CanvasAsset concurrent = findAssetByObject(context.canvasId(), objectUrl);
            if (concurrent != null) return concurrent;
            throw duplicate;
        }
        return asset;
    }

    private ImportedMedia importMediaIntoGraph(
            CanvasGraphCommandContext context,
            MorrowCanvas canvas,
            Map<String, Object> graph,
            Map<String, Object> media
    ) {
        String nodeId = blank(string(media.get("nodeId")))
                ? "media-" + UUID.randomUUID()
                : string(media.get("nodeId"));
        CanvasAsset asset = resolveMediaAsset(context, canvas, media, nodeId);
        String kind = blank(string(media.get("kind")))
                ? asset.getType() : normalizeMediaKind(string(media.get("kind")));
        if (!"image".equals(kind) && !"video".equals(kind)) {
            throw new CanvasGraphCommandException("MEDIA_KIND_UNSUPPORTED", "importMedia 只支持 image/video");
        }

        Map<String, Object> nodeSpec = new LinkedHashMap<>();
        nodeSpec.put("id", nodeId);
        nodeSpec.put("type", kind);
        if (media.get("position") instanceof Map<?, ?> position) nodeSpec.put("position", position);
        nodeSpec.put("width", media.get("width"));
        nodeSpec.put("height", media.get("height"));
        Map<String, Object> data = mutableMap(media.get("data"));
        data.put("assetId", asset.getId());
        data.put("assetUrl", asset.getObjectUrl());
        data.put("mimeType", asset.getMimeType());
        data.put("status", "success");
        data.put("label", firstNonBlank(
                string(media.get("label")), string(media.get("name")), asset.getObjectUrl()));
        nodeSpec.put("data", data);
        return new ImportedMedia(createNodeInternal(graph, nodeSpec), asset);
    }

    private record ImportedMedia(Map<String, Object> node, CanvasAsset asset) {
    }

    private CanvasAsset findAssetByObject(String canvasId, String objectUrl) {
        CanvasAsset asset = assetMapper.selectOne(new LambdaQueryWrapper<CanvasAsset>()
                .eq(CanvasAsset::getCanvasId, canvasId)
                .eq(CanvasAsset::getObjectUrl, objectUrl)
                .last("LIMIT 1"));
        if (asset != null) asset.setObjectUrl(fileUrlSigner.signIfLocal(asset.getObjectUrl()));
        return asset;
    }

    private Map<String, Object> mutableGraph(Map<String, Object> source) {
        Map<String, Object> graph = source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
        graph.put("schemaVersion", 2);
        graph.put("nodes", new ArrayList<>(listOfMaps(graph.get("nodes"))));
        graph.put("edges", new ArrayList<>(listOfMaps(graph.get("edges"))));
        graph.putIfAbsent("viewport", new LinkedHashMap<>(Map.of("x", 0, "y", 0, "zoom", 1)));
        graph.put("generationRecords", new ArrayList<>(listOfMaps(graph.get("generationRecords"))));
        return graph;
    }

    private static Map<String, Object> findNode(Map<String, Object> graph, String nodeId) {
        if (blank(nodeId)) return null;
        return listOfMaps(graph.get("nodes")).stream()
                .filter(node -> Objects.equals(nodeId, string(node.get("id"))))
                .findFirst()
                .orElse(null);
    }

    /**
     * 就地取图里的节点/连线列表：返回的元素是图里的**真实对象**，改动能落库。
     *
     * <p>{@link #listOfMaps} 会为每个元素新建 LinkedHashMap（副本），因此
     * {@link #findNode}/{@link #findEdge} 只能用于只读判断；任何形如
     * {@code node.put("data", ...)} 的写入都必须走 {@link #findLiveNode}/{@link #findLiveEdge}，
     * 否则写入会静默丢失（既存 update_node / layout_graph 无效的根因）。
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> liveElements(Map<String, Object> graph, String key) {
        Object value = graph.get(key);
        if (!(value instanceof List<?> raw)) {
            List<Map<String, Object>> created = new ArrayList<>();
            graph.put(key, created);
            return created;
        }
        List<Object> list = (List<Object>) raw;
        for (int index = 0; index < list.size(); index++) {
            Object item = list.get(index);
            if (!(item instanceof Map<?, ?> map)) continue;
            if (item instanceof LinkedHashMap<?, ?>) continue;
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((entryKey, entryValue) -> normalized.put(String.valueOf(entryKey), entryValue));
            list.set(index, normalized);
        }
        return (List<Map<String, Object>>) raw;
    }

    /** 就地查找节点：返回图里的真实对象（可写），找不到返回 null。 */
    private static Map<String, Object> findLiveNode(Map<String, Object> graph, String nodeId) {
        if (blank(nodeId)) return null;
        for (Map<String, Object> node : liveElements(graph, "nodes")) {
            if (Objects.equals(nodeId, string(node.get("id")))) return node;
        }
        return null;
    }

    /** 就地查找连线（source→target），返回图里的真实对象（可写）。 */
    private static Map<String, Object> findLiveEdge(Map<String, Object> graph, String sourceId, String targetId) {
        for (Map<String, Object> edge : liveElements(graph, "edges")) {
            if (Objects.equals(sourceId, string(edge.get("source")))
                    && Objects.equals(targetId, string(edge.get("target")))) {
                return edge;
            }
        }
        return null;
    }

    private static String kindOf(Map<String, Object> node) {
        return CanvasNodeRegistry.normalizeAlias(firstNonBlank(
                string(mapOrEmpty(node.get("data")).get("kind")),
                string(node.get("type"))));
    }

    /**
     * 同类结果槽在整张画布内的下一个可用编号（「结果 N」→ N+1）。
     * 逐生产节点调用时不再各自产出「结果 1」，用户能在画布上区分槽位。
     */
    private static int nextResultSlotIndex(Map<String, Object> graph, String kind) {
        int max = 0;
        for (Map<String, Object> node : listOfMaps(graph.get("nodes"))) {
            if (!kind.equals(normalizeMediaKind(kindOf(node)))) continue;
            String label = string(mapOrEmpty(node.get("data")).get("label"));
            Matcher matcher = RESULT_SLOT_LABEL.matcher(label == null ? "" : label.trim());
            if (matcher.matches()) {
                max = Math.max(max, integer(matcher.group(1), 0));
            }
        }
        return max + 1;
    }

    private static boolean hasPath(List<Map<String, Object>> edges, String from, String to) {
        Map<String, List<String>> adjacency = new HashMap<>();
        for (Map<String, Object> edge : edges) {
            adjacency.computeIfAbsent(string(edge.get("source")), ignored -> new ArrayList<>())
                    .add(string(edge.get("target")));
        }
        Deque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(from);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (Objects.equals(current, to)) return true;
            if (!visited.add(current)) continue;
            queue.addAll(adjacency.getOrDefault(current, List.of()));
        }
        return false;
    }

    private static Map<String, Object> nextFreePosition(List<Map<String, Object>> nodes) {
        Set<String> occupied = new HashSet<>();
        for (Map<String, Object> node : nodes) {
            Map<String, Object> position = map(node.get("position"));
            occupied.add(Math.round(number(position.get("x"), 0)) + ":" + Math.round(number(position.get("y"), 0)));
        }
        for (int index = 0; index < 10_000; index++) {
            int column = index % 3;
            int row = index / 3;
            int x = 300 + column * 480;
            int y = 200 + row * 360;
            if (!occupied.contains(x + ":" + y)) {
                return new LinkedHashMap<>(Map.of("x", x, "y", y));
            }
        }
        return new LinkedHashMap<>(Map.of("x", 300, "y", 200));
    }

    private static int nextPortOrder(List<Map<String, Object>> edges, String targetId, String targetHandle) {
        Set<Integer> used = new LinkedHashSet<>();
        for (Map<String, Object> edge : edges) {
            if (Objects.equals(targetId, string(edge.get("target")))
                    && Objects.equals(targetHandle, string(edge.get("targetHandle")))) {
                Integer order = integerObject(mapOrEmpty(edge.get("data")).get("portOrder"));
                if (order != null && order >= 0) used.add(order);
            }
        }
        int order = 0;
        while (used.contains(order)) order++;
        return order;
    }

    private static List<LayoutPosition> simpleLayout(
            List<Map<String, Object>> nodes,
            String mode,
            double baseX,
            double baseY,
            double gapX,
            double gapY,
            int columns
    ) {
        List<Map<String, Object>> ordered = new ArrayList<>(nodes);
        ordered.sort((left, right) -> {
            Map<String, Object> a = map(left.get("position"));
            Map<String, Object> b = map(right.get("position"));
            int byY = Double.compare(number(a.get("y"), 0), number(b.get("y"), 0));
            return byY != 0 ? byY : Double.compare(number(a.get("x"), 0), number(b.get("x"), 0));
        });
        int effectiveColumns = columns > 0
                ? columns
                : "horizontal".equals(mode)
                    ? ordered.size()
                    : "vertical".equals(mode) ? 1 : Math.max(1, (int) Math.ceil(Math.sqrt(ordered.size())));
        List<LayoutPosition> result = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            int column = "vertical".equals(mode) ? 0 : index % effectiveColumns;
            int row = "vertical".equals(mode) ? index : "horizontal".equals(mode) ? 0 : index / effectiveColumns;
            result.add(new LayoutPosition(
                    string(ordered.get(index).get("id")),
                    baseX + column * gapX,
                    baseY + row * gapY));
        }
        return result;
    }

    private static List<LayoutPosition> dagLayout(
            Map<String, Object> graph,
            List<Map<String, Object>> nodes,
            double baseX,
            double baseY,
            double gapX,
            double gapY
    ) {
        Set<String> nodeIds = nodes.stream().map(node -> string(node.get("id"))).collect(java.util.stream.Collectors.toSet());
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        nodeIds.forEach(id -> indegree.put(id, 0));
        for (Map<String, Object> edge : listOfMaps(graph.get("edges"))) {
            String source = string(edge.get("source"));
            String target = string(edge.get("target"));
            if (!nodeIds.contains(source) || !nodeIds.contains(target)) continue;
            outgoing.computeIfAbsent(source, ignored -> new ArrayList<>()).add(target);
            indegree.put(target, indegree.getOrDefault(target, 0) + 1);
        }
        Map<String, Integer> level = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                queue.add(id);
                level.put(id, 0);
            }
        });
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            int nextLevel = level.getOrDefault(current, 0) + 1;
            for (String next : outgoing.getOrDefault(current, List.of())) {
                level.merge(next, nextLevel, Math::max);
                int degree = indegree.merge(next, -1, Integer::sum);
                if (degree == 0) queue.addLast(next);
            }
        }
        Map<Integer, Integer> rows = new HashMap<>();
        List<LayoutPosition> result = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            String id = string(node.get("id"));
            int nodeLevel = level.getOrDefault(id, 0);
            int row = rows.getOrDefault(nodeLevel, 0);
            rows.put(nodeLevel, row + 1);
            result.add(new LayoutPosition(id, baseX + nodeLevel * gapX, baseY + row * gapY));
        }
        return result;
    }

    private static double minPosition(List<Map<String, Object>> nodes, String axis) {
        return nodes.stream()
                .map(node -> map(node.get("position")))
                .mapToDouble(position -> number(position.get(axis), 0))
                .min()
                .orElse(0);
    }

    private static Map<String, Object> ok(String command) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("command", command);
        return out;
    }

    private static Map<String, Object> writeResult(
            String command,
            CanvasGraphCommandContext context,
            int revision,
            Map<String, Object> graph
    ) {
        Map<String, Object> out = ok(command);
        out.put("revision", revision);
        out.put("nodeCount", list(graph.get("nodes")).size());
        out.put("edgeCount", list(graph.get("edges")).size());
        out.put("operationKey", context.operationKey());
        putRun(out, context);
        return out;
    }

    private static void putRun(Map<String, Object> out, CanvasGraphCommandContext context) {
        if (context != null && !blank(context.runId())) out.put("runId", context.runId());
    }

    private static Map<String, Object> assetMap(CanvasAsset asset) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", asset.getId());
        out.put("canvasId", asset.getCanvasId());
        out.put("projectId", asset.getProjectId());
        out.put("type", asset.getType());
        out.put("mimeType", asset.getMimeType());
        out.put("objectUrl", asset.getObjectUrl());
        out.put("objectKey", asset.getObjectKey());
        out.put("status", asset.getStatus());
        return out;
    }

    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> values)) return new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : values) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> normalized = new LinkedHashMap<>();
                map.forEach((key, entry) -> normalized.put(String.valueOf(key), entry));
                out.add(normalized);
            }
        }
        return out;
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> values ? values : List.of();
    }

    private static Map<String, Object> mutableMap(Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, entry) -> out.put(String.valueOf(key), entry));
        }
        return out;
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> map)) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((key, entry) -> out.put(String.valueOf(key), entry));
        return out;
    }

    private static Map<String, Object> mapOrEmpty(Object value) {
        Map<String, Object> out = map(value);
        return out == null ? new LinkedHashMap<>() : out;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream()
                .map(CanvasGraphCommandServiceImpl::string)
                .filter(item -> !blank(item))
                .toList();
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int integer(Object value, int fallback) {
        Integer parsed = integerObject(value);
        return parsed == null ? fallback : parsed;
    }

    private static Integer integerObject(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return null;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        if (value == null) return fallback;
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String normalizeMediaKind(String value) {
        if (value == null) return "";
        return switch (value.trim().toLowerCase()) {
            case "image", "图片" -> "image";
            case "video", "视频" -> "video";
            case "audio", "音频" -> "audio";
            default -> value.trim();
        };
    }

    private static String detectMediaKind(String mimeType, String name, String url) {
        String source = firstNonBlank(mimeType, name, url);
        String lower = source == null ? "" : source.toLowerCase();
        if (lower.startsWith("video/") || lower.matches(".*\\.(mp4|mov|webm)$")) return "video";
        return "image";
    }

    private static String stripQuery(String url) {
        int query = url.indexOf('?');
        int fragment = url.indexOf('#');
        int end = url.length();
        if (query >= 0) end = Math.min(end, query);
        if (fragment >= 0) end = Math.min(end, fragment);
        return url.substring(0, end);
    }

    private static String libraryMediaId(String url) {
        if (blank(url)) return null;
        String path = stripQuery(url);
        String marker = "/api/library/media/";
        int start = path.indexOf(marker);
        if (start < 0) return null;
        String rest = path.substring(start + marker.length());
        int slash = rest.indexOf('/');
        String id = slash < 0 ? rest : rest.substring(0, slash);
        return blank(id) ? null : id;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!blank(value)) return value.trim();
        }
        return null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimSlash(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private record LayoutPosition(String nodeId, double x, double y) {
    }

    /** 一次布局的产物：被移动的节点 id 与最终坐标。 */
    private record LayoutOutcome(List<String> movedNodeIds, List<Map<String, Object>> positions) {
    }

    /** 一批结果槽的产物：节点 id、连线 id，以及用于 patch 的完整对象。 */
    private record ResultSlotBatch(List<String> nodeIds,
                                   List<String> edgeIds,
                                   List<Map<String, Object>> nodes,
                                   List<Map<String, Object>> edges) {
        private static ResultSlotBatch empty() {
            return new ResultSlotBatch(List.of(), List.of(), List.of(), List.of());
        }
    }

    /** apply_plan 阶段 1 的产物；id 为空表示交给 createNodeInternal（或素材导入）生成。 */
    private record PlannedNode(int index,
                               String ref,
                               String id,
                               String kind,
                               Map<String, Object> spec,
                               boolean creatable,
                               Map<String, Object> media) {
    }

    /** refs 表达式：{@code target} 为 ref 或既有节点 id；{@code resultIndex} 非空表示 .result.<n>。 */
    private record RefExpression(String target, Integer resultIndex) {
    }

    /** 一对可用端口：来源输出口 → 目标输入口。 */
    private record PortPair(CanvasNodeRegistry.Port output, CanvasNodeRegistry.Port input) {
    }

    /** 裁剪产物：被删掉的槽节点与其入边。 */
    private record PruneOutcome(List<String> removedNodeIds, List<String> removedEdgeIds) {
    }

    /** apply_plan 解析 refs 时的批次状态。 */
    private static final class PlanScope {

        private final Map<String, String> refIds;
        private final Map<String, List<String>> slotIdsByNode;
        private final Set<String> failedRefs;
        private final Set<String> failedIds;
        private final Map<String, String> portByTarget = new HashMap<>();

        private PlanScope(Map<String, String> refIds,
                          Map<String, List<String>> slotIdsByNode,
                          Set<String> failedRefs,
                          Set<String> failedIds) {
            this.refIds = refIds;
            this.slotIdsByNode = slotIdsByNode;
            this.failedRefs = failedRefs;
            this.failedIds = failedIds;
        }

        private Map<String, List<String>> slotIdsByNode() {
            return slotIdsByNode;
        }

        private Set<String> failedRefs() {
            return failedRefs;
        }

        private Set<String> failedIds() {
            return failedIds;
        }

        /** ref 先按本批次映射解析，其次当作画布既有节点 id。 */
        private String resolveNodeId(Map<String, Object> graph, String ref) {
            String planned = refIds.get(ref);
            if (!blank(planned)) return planned;
            return findNode(graph, ref) == null ? null : ref;
        }

        private String preferredPort(String targetId) {
            return portByTarget.get(targetId);
        }

        private void preferPort(String targetId, String portId) {
            portByTarget.put(targetId, portId);
        }
    }
}
