package com.moon.xiaoyuecanvas.service;

import com.moon.moonagent.entity.MorrowCanvas;
import com.moon.moonagent.security.FileUrlSigner;
import com.moon.moonagent.service.FileStorageService;
import com.moon.moonagent.service.MorrowCanvasService;
import com.moon.xiaoyuecanvas.dto.response.CanvasGraphResponse;
import com.moon.xiaoyuecanvas.mapper.CanvasAssetMapper;
import com.moon.xiaoyuecanvas.mapper.CanvasGraphOperationMapper;
import com.moon.xiaoyuecanvas.service.impl.CanvasGraphCommandServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * apply_plan 的改图能力：{@code updates}（改已有节点）与 {@code markResultSlots}（把已有空节点标记为结果槽）。
 *
 * <p>这两条能力是工具收敛（21 → 8）的前提：收敛后 {@code apply_plan} 是**唯一**写入口，
 * 必须能覆盖原先 create_node / update_node / mark_result_slots / create_edges 的全部语义，
 * 且整批只产生 **1 个 revision**（与分开调用相比少 reload 与少 revision）。
 */
class CanvasGraphApplyPlanEditTest {

    private CanvasGraphService graphService;
    private CanvasGraphOperationMapper operationMapper;
    private MorrowCanvasService canvasService;
    private CanvasGraphCommandService service;

    @BeforeEach
    void setUp() {
        graphService = mock(CanvasGraphService.class);
        operationMapper = mock(CanvasGraphOperationMapper.class);
        canvasService = mock(MorrowCanvasService.class);
        service = new CanvasGraphCommandServiceImpl(
                graphService,
                operationMapper,
                canvasService,
                mock(CanvasAssetService.class),
                mock(CanvasAssetMapper.class),
                mock(CanvasProjectLibraryService.class),
                mock(FileStorageService.class),
                new FileUrlSigner("test-secret", 3600),
                "http://localhost:8082");
    }

    // ── updates：改已有节点 ──────────────────────────────────────────────────

    @Test
    void updatesOnlyPlanChangesExistingNodeWithoutCreatingAnything() {
        CanvasGraphCommandContext context = context("op-update-only");
        Map<String, Object> graph = graphWithStandardNodes();
        stubGraph(graph, 3, 4);

        Map<String, Object> result = service.applyPlan(context, Map.of(
                "updates", List.of(Map.of(
                        "nodeId", "producer",
                        "patch", Map.of("prompt", "一只橘猫在窗台", "label", "新的文生图")))), false, false);

        assertEquals(4, result.get("revision"), "整批只 +1 revision");
        verify(graphService, times(1)).save(eq("user-1"), eq("canvas-1"), anyMap(), eq(3));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(graphService).save(eq("user-1"), eq("canvas-1"), captor.capture(), eq(3));
        Map<String, Object> saved = node(captor.getValue(), "producer");
        Map<String, Object> data = map(saved.get("data"));
        assertEquals("一只橘猫在窗台", data.get("prompt"), "prompt 必须落进 data");
        assertEquals("新的文生图", data.get("label"), "扁平 label 等价于 data.label");
        assertEquals(3, nodes(captor.getValue()).size(), "纯改图不得新建节点");

        // 改过的节点必须进增量 patch，否则前端只能整图重载
        List<?> patchNodes = (List<?>) map(result.get("patch")).get("nodes");
        assertTrue(patchNodes.stream().anyMatch(item -> "producer".equals(map(item).get("id"))),
                "patch.nodes 必须包含被改的节点");
    }

    @Test
    void updatesRejectUnknownNodeId() {
        CanvasGraphCommandContext context = context("op-update-missing");
        stubGraph(graphWithStandardNodes(), 3, 4);

        assertThrows(RuntimeException.class, () -> service.applyPlan(context, Map.of(
                "updates", List.of(Map.of("nodeId", "nope", "patch", Map.of("label", "x")))), false, false));
    }

    @Test
    void updatesCannotRenameIdOrType() {
        CanvasGraphCommandContext context = context("op-update-immutable");
        Map<String, Object> graph = graphWithStandardNodes();
        stubGraph(graph, 3, 4);

        service.applyPlan(context, Map.of(
                "updates", List.of(Map.of("nodeId", "producer",
                        "patch", Map.of("id", "hacked", "type", "video", "label", "ok")))), false, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(graphService).save(eq("user-1"), eq("canvas-1"), captor.capture(), eq(3));
        Map<String, Object> saved = node(captor.getValue(), "producer");
        assertEquals("textToImage", saved.get("type"), "type 不可改");
        Map<String, Object> data = map(saved.get("data"));
        assertEquals("ok", data.get("label"));
        // id 是节点表的键，不是 data 字段
        assertEquals("producer", saved.get("id"));
    }

    // ── markResultSlots：把已有空节点标记为结果槽 ────────────────────────────

    @Test
    void markResultSlotsLinksExistingEmptyNodesWithoutCreatingThem() {
        CanvasGraphCommandContext context = context("op-mark-slots");
        Map<String, Object> graph = graphWithStandardNodes();
        stubGraph(graph, 3, 4);

        Map<String, Object> result = service.applyPlan(context, Map.of(
                "markResultSlots", List.of(Map.of(
                        "producerNodeId", "producer",
                        "nodeIds", List.of("image-1")))), false, false);

        assertEquals(4, result.get("revision"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(graphService).save(eq("user-1"), eq("canvas-1"), captor.capture(), eq(3));
        Map<String, Object> saved = captor.getValue();
        assertEquals(3, nodes(saved).size(), "标记结果槽只补连线，不新建节点");

        Map<String, Object> edge = edges(saved).stream()
                .filter(item -> "producer".equals(item.get("source")) && "image-1".equals(item.get("target")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("必须补出 producer → image-1 的连线"));
        Map<String, Object> edgeData = map(edge.get("data"));
        assertEquals(true, edgeData.get("resultSlot"), "连线必须带 resultSlot 标记");
        assertEquals(0, edgeData.get("resultSlotOrder"), "首个槽位顺序为 0");
    }

    @Test
    void markResultSlotsRejectsNodeThatAlreadyHasAsset() {
        CanvasGraphCommandContext context = context("op-mark-slots-nonempty");
        Map<String, Object> graph = graphWithStandardNodes();
        // 让目标槽位变成"已有素材"
        Map<String, Object> image = node(graph, "image-1");
        Map<String, Object> data = map(image.get("data"));
        data.put("assetId", "asset-1");
        image.put("data", data);
        stubGraph(graph, 3, 4);

        // 默认 continueOnError=false：整批不落库并抛错（与 apply_plan 既有策略一致）
        Exception error = assertThrows(RuntimeException.class, () -> service.applyPlan(context, Map.of(
                "markResultSlots", List.of(Map.of(
                        "producerNodeId", "producer",
                        "nodeIds", List.of("image-1")))), false, false));
        verify(graphService, times(0)).save(anyString(), anyString(), anyMap(), anyInt());
        assertTrue(String.valueOf(error.getMessage()).contains("未通过校验"),
                "已有素材的节点不能作为结果槽：" + error.getMessage());
    }

    // ── 混合：建 + 改 + 标记，同一个 revision ───────────────────────────────

    @Test
    void mixedPlanCreatesUpdatesAndMarksInSingleRevision() {
        CanvasGraphCommandContext context = context("op-mixed");
        Map<String, Object> graph = graphWithStandardNodes();
        stubGraph(graph, 3, 4);

        Map<String, Object> newNode = new LinkedHashMap<>();
        newNode.put("ref", "g1");
        newNode.put("type", "textToVideo");
        newNode.put("label", "文生视频");

        Map<String, Object> result = service.applyPlan(context, Map.of(
                "nodes", List.of(newNode),
                "updates", List.of(Map.of("nodeId", "text-1", "patch", Map.of("label", "改过的提示词节点"))),
                "markResultSlots", List.of(Map.of(
                        "producerNodeId", "producer", "nodeIds", List.of("image-1")))), false, false);

        assertEquals(4, result.get("revision"), "建节点 + 改节点 + 标记槽必须合并为 1 个 revision");
        verify(graphService, times(1)).save(eq("user-1"), eq("canvas-1"), anyMap(), eq(3));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(graphService).save(eq("user-1"), eq("canvas-1"), captor.capture(), eq(3));
        Map<String, Object> saved = captor.getValue();
        assertEquals(4, nodes(saved).size(), "原 3 个 + 新建 1 个");
        assertEquals("改过的提示词节点", map(node(saved, "text-1").get("data")).get("label"));
        assertTrue(edges(saved).stream().anyMatch(item ->
                        "producer".equals(item.get("source")) && "image-1".equals(item.get("target"))),
                "标记出的结果槽连线必须存在");
    }

    @Test
    void emptyPlanIsRejectedWithActionableMessage() {
        CanvasGraphCommandContext context = context("op-empty");
        stubGraph(graphWithStandardNodes(), 3, 4);

        Exception error = assertThrows(RuntimeException.class,
                () -> service.applyPlan(context, Map.of(), false, false));
        assertTrue(String.valueOf(error.getMessage()).contains("nodes")
                        || String.valueOf(error.getMessage()).contains("updates"),
                "错误信息应指明至少给 nodes / updates / markResultSlots 之一：" + error.getMessage());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void stubGraph(Map<String, Object> graph, int baseRevision, int nextRevision) {
        when(canvasService.get("user-1", "canvas-1")).thenReturn(canvas());
        when(graphService.load("user-1", "canvas-1"))
                .thenReturn(new CanvasGraphResponse(graph, baseRevision, "2026-10-16T00:00:00Z"));
        when(operationMapper.selectById(anyString())).thenReturn(null);
        when(operationMapper.claim(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);
        when(graphService.save(eq("user-1"), eq("canvas-1"), anyMap(), eq(baseRevision)))
                .thenAnswer(invocation -> new CanvasGraphResponse(invocation.getArgument(2), nextRevision, null));
    }

    private static CanvasGraphCommandContext context(String operationKey) {
        return new CanvasGraphCommandContext("user-1", "project-1", "canvas-1", "run-1", operationKey);
    }

    private static MorrowCanvas canvas() {
        MorrowCanvas canvas = new MorrowCanvas();
        canvas.setId("canvas-1");
        canvas.setProjectId("project-1");
        canvas.setTitle("测试画布");
        return canvas;
    }

    /** text 提示词 → textToImage 生产节点 → image 空结果占位。 */
    private static Map<String, Object> graphWithStandardNodes() {
        Map<String, Object> graph = emptyGraphMap();
        nodes(graph).add(node("text-1", "text", 0, 0, Map.of("label", "提示词")));
        nodes(graph).add(node("producer", "textToImage", 600, 0, Map.of("label", "文生图")));
        nodes(graph).add(node("image-1", "image", 1200, 0, Map.of("label", "结果 1")));
        return graph;
    }

    private static Map<String, Object> emptyGraphMap() {
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("schemaVersion", 2);
        graph.put("nodes", new ArrayList<Map<String, Object>>());
        graph.put("edges", new ArrayList<Map<String, Object>>());
        graph.put("viewport", new LinkedHashMap<>(Map.of("x", 0, "y", 0, "zoom", 1)));
        graph.put("generationRecords", new ArrayList<Map<String, Object>>());
        return graph;
    }

    private static Map<String, Object> node(String id, String type, double x, double y, Map<String, Object> data) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", type);
        node.put("position", new LinkedHashMap<>(Map.of("x", x, "y", y)));
        node.put("width", 320);
        node.put("height", 240);
        Map<String, Object> payload = new LinkedHashMap<>(data);
        payload.putIfAbsent("kind", type);
        node.put("data", payload);
        return node;
    }

    private static Map<String, Object> node(Map<String, Object> graph, String id) {
        return nodes(graph).stream()
                .filter(item -> id.equals(item.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("节点不存在: " + id));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nodes(Map<String, Object> graph) {
        return (List<Map<String, Object>>) graph.get("nodes");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> edges(Map<String, Object> graph) {
        return (List<Map<String, Object>>) graph.get("edges");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value == null ? Map.of() : (Map<String, Object>) value;
    }
}
