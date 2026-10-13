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
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
public class CanvasGraphCommandServiceImpl implements CanvasGraphCommandService {

    private static final String FILE_URL_PREFIX = "/api/files/";
    private static final int MAX_BATCH_NODES = 100;

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
            Boolean includeEdges
    ) {
        requireCanvas(context);
        CanvasGraphResponse loaded = load(context);
        Map<String, Object> graph = mutableGraph(loaded.graph());
        if (limit != null && limit > 0) {
            int from = Math.max(0, offset == null ? 0 : offset);
            List<Map<String, Object>> nodes = listOfMaps(graph.get("nodes"));
            int to = Math.min(nodes.size(), from + limit);
            graph.put("nodes", new ArrayList<>(nodes.subList(Math.min(from, nodes.size()), to)));
        }
        if (Boolean.FALSE.equals(includeEdges)) {
            graph.put("edges", new ArrayList<>());
        }
        Map<String, Object> out = ok("get_state");
        out.put("canvasId", context.canvasId());
        out.put("projectId", context.projectId());
        out.put("revision", loaded.revision());
        out.put("updatedAt", loaded.updatedAt());
        out.put("nodeCount", list(loaded.graph().get("nodes")).size());
        out.put("edgeCount", list(loaded.graph().get("edges")).size());
        out.put("graph", graph);
        putRun(out, context);
        return out;
    }

    @Override
    public Map<String, Object> listNodes(CanvasGraphCommandContext context, List<String> nodeIds) {
        requireCanvas(context);
        CanvasGraphResponse loaded = load(context);
        Set<String> filter = nodeIds == null ? Set.of() : new HashSet<>(nodeIds);
        List<Map<String, Object>> nodes = listOfMaps(loaded.graph().get("nodes")).stream()
                .filter(node -> filter.isEmpty() || filter.contains(string(node.get("id"))))
                .toList();
        Map<String, Object> out = ok("list_nodes");
        out.put("revision", loaded.revision());
        out.put("count", nodes.size());
        out.put("nodes", nodes);
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
    public Map<String, Object> listEdges(CanvasGraphCommandContext context, List<String> edgeIds) {
        requireCanvas(context);
        CanvasGraphResponse loaded = load(context);
        Set<String> filter = edgeIds == null ? Set.of() : new HashSet<>(edgeIds);
        List<Map<String, Object>> edges = listOfMaps(loaded.graph().get("edges")).stream()
                .filter(edge -> filter.isEmpty() || filter.contains(string(edge.get("id"))))
                .toList();
        Map<String, Object> out = ok("list_edges");
        out.put("revision", loaded.revision());
        out.put("count", edges.size());
        out.put("edges", edges);
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
    public Map<String, Object> createNode(CanvasGraphCommandContext context, Map<String, Object> node) {
        return idempotent(context, "create_node", () -> {
            requireCanvas(context);
            CanvasGraphResponse loaded = load(context);
            Map<String, Object> graph = mutableGraph(loaded.graph());
            List<Map<String, Object>> nodes = listOfMaps(graph.get("nodes"));
            Map<String, Object> created = createNodeInternal(graph, node);
            int revision = saveGraph(context, graph, loaded.revision());
            Map<String, Object> out = writeResult("create_node", context, revision, graph);
            out.put("nodeId", created.get("id"));
            out.put("node", created);
            return out;
        });
    }

    @Override
    @Transactional
    public Map<String, Object> createNodes(CanvasGraphCommandContext context, List<Map<String, Object>> items) {
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
            for (Map<String, Object> item : items) {
                created.add(createNodeInternal(graph, item));
            }
            int revision = saveGraph(context, graph, loaded.revision());
            Map<String, Object> out = writeResult("create_nodes", context, revision, graph);
            out.put("nodeIds", created.stream().map(node -> string(node.get("id"))).toList());
            out.put("nodes", created);
            return out;
        });
    }

    @Override
    @Transactional
    public Map<String, Object> updateNode(
            CanvasGraphCommandContext context,
            String nodeId,
            Map<String, Object> patch
    ) {
        return idempotent(context, "update_node", () -> {
            requireCanvas(context);
            if (blank(nodeId)) {
                throw new CanvasGraphCommandException("INVALID_ARGUMENTS", "nodeId 不能为空");
            }
            if (patch == null) {
                throw new CanvasGraphCommandException("INVALID_ARGUMENTS", "patch 不能为空");
            }
            CanvasGraphResponse loaded = load(context);
            Map<String, Object> graph = mutableGraph(loaded.graph());
            Map<String, Object> node = findNode(graph, nodeId);
            if (node == null) {
                throw new CanvasGraphCommandException("NODE_NOT_FOUND", "节点不存在: " + nodeId);
            }
            Map<String, Object> data = mutableMap(node.get("data"));
            patch.forEach((key, value) -> {
                if (!Set.of("id", "type", "kind").contains(key)) data.put(key, value);
            });
            node.put("data", data);
            int revision = saveGraph(context, graph, loaded.revision());
            Map<String, Object> out = writeResult("update_node", context, revision, graph);
            out.put("nodeId", nodeId);
            out.put("node", node);
            return out;
        });
    }

    @Override
    @Transactional
    public Map<String, Object> createEdge(CanvasGraphCommandContext context, Map<String, Object> edge) {
        return idempotent(context, "create_edge", () -> {
            requireCanvas(context);
            CanvasGraphResponse loaded = load(context);
            Map<String, Object> graph = mutableGraph(loaded.graph());
            Map<String, Object> created = createEdgeInternal(graph, edge);
            int revision = saveGraph(context, graph, loaded.revision());
            Map<String, Object> out = writeResult("create_edge", context, revision, graph);
            out.put("edgeId", created.get("id"));
            out.put("edge", created);
            return out;
        });
    }

    @Override
    @Transactional
    public Map<String, Object> importMedia(CanvasGraphCommandContext context, Map<String, Object> media) {
        return idempotent(context, "import_media", () -> {
            MorrowCanvas canvas = requireCanvas(context);
            CanvasGraphResponse loaded = load(context);
            Map<String, Object> graph = mutableGraph(loaded.graph());
            ImportedMedia imported = importMediaIntoGraph(context, canvas, graph, media);
            int revision = saveGraph(context, graph, loaded.revision());

            Map<String, Object> out = writeResult("import_media", context, revision, graph);
            out.put("nodeId", imported.node().get("id"));
            out.put("assetId", imported.asset().getId());
            out.put("asset", assetMap(imported.asset()));
            out.put("node", imported.node());
            return out;
        });
    }

    @Override
    @Transactional
    public Map<String, Object> importMediaBatch(
            CanvasGraphCommandContext context,
            List<Map<String, Object>> mediaItems
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
            for (Map<String, Object> media : mediaItems) {
                ImportedMedia imported = importMediaIntoGraph(context, canvas, graph, media);
                nodeIds.add(string(imported.node().get("id")));
                assetIds.add(imported.asset().getId());
                nodes.add(imported.node());
            }
            int revision = saveGraph(context, graph, loaded.revision());
            Map<String, Object> out = writeResult("import_media_batch", context, revision, graph);
            out.put("nodeIds", nodeIds);
            out.put("assetIds", assetIds);
            out.put("nodes", nodes);
            return out;
        });
    }

    @Override
    @Transactional
    public Map<String, Object> createResultSlots(CanvasGraphCommandContext context, Map<String, Object> args) {
        return idempotent(context, "create_result_slots", () -> {
            requireCanvas(context);
            String producerNodeId = string(args.get("producerNodeId"));
            String kind = normalizeMediaKind(string(args.get("kind")));
            int count = integer(args.get("count"), 0);
            if (blank(producerNodeId)) {
                throw new CanvasGraphCommandException("INVALID_ARGUMENTS", "producerNodeId 不能为空");
            }
            if (!"image".equals(kind) && !"video".equals(kind)) {
                throw new CanvasGraphCommandException("INVALID_ARGUMENTS", "kind 必须是 image/video");
            }
            if (count < 1 || count > 12) {
                throw new CanvasGraphCommandException("INVALID_ARGUMENTS", "count 必须是 1~12");
            }

            CanvasGraphResponse loaded = load(context);
            Map<String, Object> graph = mutableGraph(loaded.graph());
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
            Map<String, Object> position = map(args.get("position"));
            double producerX = number(mapOrEmpty(producer.get("position")).get("x"), 0);
            double producerY = number(mapOrEmpty(producer.get("position")).get("y"), 0);
            double producerWidth = number(producer.get("width"), producerType.width());
            double gapY = number(args.get("gapY"), slotType.height() + 40);
            double originX = position == null ? producerX + producerWidth + 160 : number(position.get("x"), producerX);
            double originY = position == null ? producerY : number(position.get("y"), producerY);

            List<String> nodeIds = new ArrayList<>();
            List<String> edgeIds = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                Map<String, Object> slot = createNodeInternal(graph, Map.of(
                        "type", kind,
                        "position", Map.of("x", originX, "y", originY + index * gapY),
                        "data", Map.of(
                                "label", "结果 " + (index + 1),
                                "status", "idle")));
                nodeIds.add(string(slot.get("id")));
                Map<String, Object> edge = createEdgeInternal(graph, Map.of(
                        "source", producerNodeId,
                        "sourceHandle", output.id(),
                        "target", slot.get("id"),
                        "targetHandle", "origin",
                        "data", Map.of("resultSlot", true, "resultSlotOrder", index)));
                edgeIds.add(string(edge.get("id")));
            }
            int revision = saveGraph(context, graph, loaded.revision());
            Map<String, Object> out = writeResult("create_result_slots", context, revision, graph);
            out.put("nodeIds", nodeIds);
            out.put("edgeIds", edgeIds);
            return out;
        });
    }

    @Override
    @Transactional
    public Map<String, Object> markResultSlots(CanvasGraphCommandContext context, Map<String, Object> args) {
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
                Map<String, Object> existing = findEdge(graph, producerNodeId, nodeId);
                int order = startOrder + index;
                if (existing != null) {
                    Map<String, Object> data = mutableMap(existing.get("data"));
                    data.put("resultSlot", true);
                    data.put("resultSlotOrder", order);
                    existing.put("data", data);
                    edgeIds.add(string(existing.get("id")));
                } else {
                    Map<String, Object> edge = createEdgeInternal(graph, Map.of(
                            "source", producerNodeId,
                            "sourceHandle", output.id(),
                            "target", nodeId,
                            "targetHandle", "origin",
                            "data", Map.of("resultSlot", true, "resultSlotOrder", order)));
                    edgeIds.add(string(edge.get("id")));
                }
            }
            int revision = saveGraph(context, graph, loaded.revision());
            Map<String, Object> out = writeResult("mark_result_slots", context, revision, graph);
            out.put("nodeIds", nodeIds);
            out.put("edgeIds", edgeIds);
            return out;
        });
    }

    @Override
    @Transactional
    public Map<String, Object> layoutGraph(CanvasGraphCommandContext context, Map<String, Object> args) {
        return idempotent(context, "layout_graph", () -> {
            requireCanvas(context);
            CanvasGraphResponse loaded = load(context);
            Map<String, Object> graph = mutableGraph(loaded.graph());
            List<Map<String, Object>> nodes = listOfMaps(graph.get("nodes"));
            if (nodes.isEmpty()) {
                Map<String, Object> out = writeResult("layout_graph", context, loaded.revision(), graph);
                out.put("movedNodeIds", List.of());
                out.put("positions", List.of());
                return out;
            }
            Set<String> selected = new HashSet<>(strings(args.get("nodeIds")));
            List<Map<String, Object>> scoped = nodes.stream()
                    .filter(node -> selected.isEmpty() || selected.contains(string(node.get("id"))))
                    .toList();
            if (scoped.isEmpty()) {
                throw new CanvasGraphCommandException("NODE_NOT_FOUND", "没有可排布的节点");
            }
            String mode = firstNonBlank(string(args.get("mode")), "grid");
            double gapX = number(args.get("gapX"), 520);
            double gapY = number(args.get("gapY"), 420);
            double baseX = number(mapOrEmpty(args.get("anchor")).get("x"), minPosition(scoped, "x"));
            double baseY = number(mapOrEmpty(args.get("anchor")).get("y"), minPosition(scoped, "y"));
            List<LayoutPosition> positions = "dag".equals(mode)
                    ? dagLayout(graph, scoped, baseX, baseY, gapX, gapY)
                    : simpleLayout(scoped, mode, baseX, baseY, gapX, gapY, integer(args.get("columns"), 0));
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
            int revision = saveGraph(context, graph, loaded.revision());
            Map<String, Object> out = writeResult("layout_graph", context, revision, graph);
            out.put("movedNodeIds", moved);
            out.put("positions", serialized);
            return out;
        });
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
        String kind = CanvasNodeRegistry.normalizeAlias(
                firstNonBlank(
                        string(spec.get("type")),
                        string(spec.get("kind")),
                        string(mapOrEmpty(spec.get("data")).get("kind"))));
        CanvasNodeRegistry.NodeType definition = CanvasNodeRegistry.require(kind);
        List<Map<String, Object>> nodes = listOfMaps(graph.get("nodes"));
        String id = firstNonBlank(string(spec.get("id")), kind + "-" + UUID.randomUUID());
        if (findNode(graph, id) != null) {
            throw new CanvasGraphCommandException("NODE_ALREADY_EXISTS", "节点 ID 已存在: " + id);
        }
        Map<String, Object> position = map(spec.get("position"));
        if (position == null) position = nextFreePosition(nodes);
        Map<String, Object> data = mutableMap(spec.get("data"));
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

    private Map<String, Object> findNode(Map<String, Object> graph, String nodeId) {
        if (blank(nodeId)) return null;
        return listOfMaps(graph.get("nodes")).stream()
                .filter(node -> Objects.equals(nodeId, string(node.get("id"))))
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> findEdge(Map<String, Object> graph, String sourceId, String targetId) {
        return listOfMaps(graph.get("edges")).stream()
                .filter(edge -> Objects.equals(sourceId, string(edge.get("source")))
                        && Objects.equals(targetId, string(edge.get("target"))))
                .findFirst()
                .orElse(null);
    }

    private static String kindOf(Map<String, Object> node) {
        return CanvasNodeRegistry.normalizeAlias(firstNonBlank(
                string(mapOrEmpty(node.get("data")).get("kind")),
                string(node.get("type"))));
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
}
