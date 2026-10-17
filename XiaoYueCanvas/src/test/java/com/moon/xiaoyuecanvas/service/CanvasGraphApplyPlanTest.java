package com.moon.xiaoyuecanvas.service;

import com.moon.moonagent.entity.MorrowCanvas;
import com.moon.moonagent.security.FileUrlSigner;
import com.moon.moonagent.service.FileStorageService;
import com.moon.moonagent.service.MorrowCanvasService;
import com.moon.xiaoyuecanvas.dto.response.CanvasGraphResponse;
import com.moon.xiaoyuecanvas.entity.CanvasGraphOperation;
import com.moon.xiaoyuecanvas.mapper.CanvasAssetMapper;
import com.moon.xiaoyuecanvas.mapper.CanvasGraphOperationMapper;
import com.moon.xiaoyuecanvas.service.impl.CanvasGraphCommandServiceImpl;
import com.moon.xiaoyuecanvas.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WS2 新增能力：批量连线（all-or-nothing）、resultSlots 折进节点规格、apply_plan 整图提交、
 * 读工具瘦身与分页、update_node 扁平/嵌套等价。
 */
class CanvasGraphApplyPlanTest {

    private CanvasGraphService graphService;
    private CanvasGraphOperationMapper operationMapper;
    private MorrowCanvasService canvasService;
    private CanvasAssetMapper assetMapper;
    private CanvasGraphCommandService service;

    @BeforeEach
    void setUp() {
        graphService = mock(CanvasGraphService.class);
        operationMapper = mock(CanvasGraphOperationMapper.class);
        canvasService = mock(MorrowCanvasService.class);
        assetMapper = mock(CanvasAssetMapper.class);
        service = new CanvasGraphCommandServiceImpl(
                graphService,
                operationMapper,
                canvasService,
                mock(CanvasAssetService.class),
                assetMapper,
                mock(CanvasProjectLibraryService.class),
                mock(FileStorageService.class),
                new FileUrlSigner("test-secret", 3600),
                "http://localhost:8082");
    }

    // ── 批量连线 ───────────────────────────────────────────────────────────────

    @Test
    void createEdgesUsesOneSaveAndReturnsChangePatch() {
        CanvasGraphCommandContext context = context("op-edges");
        Map<String, Object> graph = graphWithStandardNodes();
        stubGraph(graph, 3, 4);

        Map<String, Object> result = service.createEdges(context, List.of(
                edgeSpec("text-1", "producer", "text", "prompt"),
                edgeSpec("producer", "image-1", "image", "origin")), false);

        assertEquals(4, result.get("revision"));
        assertEquals(2, ((List<?>) result.get("edgeIds")).size());
        verify(graphService, times(1)).save(eq("user-1"), eq("canvas-1"), anyMap(), eq(3));

        Map<String, Object> patch = map(result.get("patch"));
        assertEquals(2, ((List<?>) patch.get("edges")).size(), "patch 必须带本次新增的完整边");
        assertEquals(List.of(), patch.get("removedNodeIds"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(graphService).save(eq("user-1"), eq("canvas-1"), captor.capture(), eq(3));
        assertEquals(2, edges(captor.getValue()).size(), "两条边必须真的落进保存的图里");
    }

    @Test
    void createEdgesIsAllOrNothingAndReportsEveryFailureInOneShot() {
        CanvasGraphCommandContext context = context("op-edges-bad");
        Map<String, Object> graph = graphWithStandardNodes();
        stubGraph(graph, 3, 4);

        CanvasGraphCommandException error = assertThrows(CanvasGraphCommandException.class, () ->
                service.createEdges(context, List.of(
                        edgeSpec("text-1", "producer", "text", "prompt"),
                        // 图片节点接提示词端口 → 端口类型不匹配
                        edgeSpec("image-1", "producer", "image", "prompt"),
                        // 自环
                        edgeSpec("text-1", "text-1", "text", "prompt")), false));

        assertEquals("EDGE_BATCH_INVALID", error.code());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) error.details().get("errors");
        assertEquals(2, errors.size(), "两条失败边必须一次性全部返回：" + errors);
        assertEquals(List.of(1, 2), errors.stream().map(item -> item.get("index")).toList());
        assertEquals(List.of("PORT_KIND_MISMATCH", "SELF_LOOP"),
                errors.stream().map(item -> item.get("code")).toList());
        verify(graphService, never()).save(anyString(), anyString(), anyMap(), anyInt());
        assertEquals(0, edges(graph).size(), "整批不落库：内存图也不保留半成品");
    }

    // ── resultSlots 折进节点规格 ────────────────────────────────────────────────

    @Test
    void createNodesFoldsResultSlotsInOneRevisionWithCanvasWideNumbering() {
        CanvasGraphCommandContext context = context("op-nodes-slots");
        Map<String, Object> graph = emptyGraphMap();
        nodes(graph).add(node("slot-existing", "image", 900, 0, Map.of("label", "结果 1")));
        stubGraph(graph, 5, 6);

        Map<String, Object> result = service.createNodes(context, List.of(
                nodeSpec("imageGeneration", "场景01", Map.of("count", 2)),
                nodeSpec("imageGeneration", "场景02", Map.of("kind", "image", "count", 1))), false);

        assertEquals(6, result.get("revision"));
        assertEquals(2, ((List<?>) result.get("nodeIds")).size());
        List<String> slotIds = (List<String>) result.get("resultSlotIds");
        assertEquals(3, slotIds.size());
        verify(graphService, times(1)).save(eq("user-1"), eq("canvas-1"), anyMap(), eq(5));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(graphService).save(eq("user-1"), eq("canvas-1"), captor.capture(), eq(5));
        assertEquals(List.of("结果 1", "结果 2", "结果 3", "结果 4"), slotLabels(captor.getValue()));
        // 每个槽都连回各自的生产节点
        assertEquals(3, edges(captor.getValue()).size());
        Map<String, Object> patch = map(result.get("patch"));
        assertEquals(5, ((List<?>) patch.get("nodes")).size(), "patch = 2 个新节点 + 3 个结果槽");
    }

    @Test
    void resultSlotsOnNonProducerNodeIsRejectedWithoutSaving() {
        CanvasGraphCommandContext context = context("op-nodes-slots-bad");
        Map<String, Object> graph = emptyGraphMap();
        stubGraph(graph, 1, 2);

        CanvasGraphCommandException error = assertThrows(CanvasGraphCommandException.class, () ->
                service.createNodes(context, List.of(nodeSpec("text", "纯文字", Map.of("kind", "image"))), false));

        assertEquals("INVALID_ARGUMENTS", error.code());
        assertTrue(error.getMessage().contains("没有 image 输出"), error.getMessage());
        verify(graphService, never()).save(anyString(), anyString(), anyMap(), anyInt());
    }

    // ── apply_plan ────────────────────────────────────────────────────────────

    @Test
    void applyPlanWiresRefsWithAutoPortsAndOrderedPortOrder() {
        CanvasGraphCommandContext context = context("op-plan-refs");
        Map<String, Object> graph = emptyGraphMap();
        stubGraph(graph, 0, 1);

        Map<String, Object> result = service.applyPlan(context, Map.of("nodes", List.of(
                Map.of("ref", "m1", "type", "image", "label", "白膜01"),
                Map.of("ref", "m2", "type", "image", "label", "白膜02"),
                Map.of("ref", "g1", "type", "videoGeneration", "label", "转场01",
                        "mode", "firstLastFrame", "refs", List.of("m1", "m2")))), false, false);

        assertEquals(1, result.get("revision"));
        verify(graphService, times(1)).save(eq("user-1"), eq("canvas-1"), anyMap(), eq(0));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(graphService).save(eq("user-1"), eq("canvas-1"), captor.capture(), eq(0));
        List<Map<String, Object>> created = edges(captor.getValue());
        assertEquals(2, created.size());
        // 「m1 → g1」自动落到 videoGeneration 唯一兼容的 reference 端口，并按 refs 顺序拿 0/1
        assertEquals("image", created.get(0).get("sourceHandle"), "源端口取 image 节点的输出 image");
        assertEquals("reference", created.get(0).get("targetHandle"));
        assertEquals(0, map(created.get(0).get("data")).get("portOrder"));
        assertEquals(1, map(created.get(1).get("data")).get("portOrder"));

        @SuppressWarnings("unchecked")
        Map<String, String> refs = (Map<String, String>) result.get("refs");
        assertEquals(3, refs.size());
        assertEquals(created.get(0).get("source"), refs.get("m1"));
        assertEquals(created.get(1).get("source"), refs.get("m2"));
        assertEquals(3, ((List<?>) map(result.get("patch")).get("nodes")).size());
    }

    @Test
    void applyPlanResolvesResultSlotRefsInDeclaredOrder() {
        CanvasGraphCommandContext context = context("op-plan-slot-refs");
        Map<String, Object> graph = emptyGraphMap();
        stubGraph(graph, 7, 8);

        Map<String, Object> result = service.applyPlan(context, Map.of("nodes", List.of(
                Map.of("ref", "m1", "type", "image", "label", "白膜"),
                Map.of("ref", "g1", "type", "imageGeneration", "label", "场景",
                        "refs", List.of("m1"), "resultSlots", Map.of("count", 2)),
                Map.of("ref", "v1", "type", "videoGeneration", "label", "转场",
                        "refs", List.of("g1.result.0", "g1.result.1")))), false, false);

        assertEquals(8, result.get("revision"));
        @SuppressWarnings("unchecked")
        List<String> slotIds = (List<String>) result.get("resultSlotIds");
        assertEquals(2, slotIds.size());
        // 1 条 m1→g1 参考连线 + 2 条 生产节点→结果槽 + 2 条 结果槽→v1
        assertEquals(5, ((List<?>) result.get("edgeIds")).size());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(graphService, times(1)).save(eq("user-1"), eq("canvas-1"), captor.capture(), eq(7));
        List<Map<String, Object>> toVideo = edges(captor.getValue()).stream()
                .filter(edge -> slotIds.contains(String.valueOf(edge.get("source"))))
                .toList();
        assertEquals(List.of(slotIds.get(0), slotIds.get(1)),
                toVideo.stream().map(edge -> String.valueOf(edge.get("source"))).toList(),
                "result.0 / result.1 必须按声明顺序接进 reference");
        assertEquals(List.of(0, 1), toVideo.stream()
                .map(edge -> map(edge.get("data")).get("portOrder")).toList());
    }

    @Test
    void applyPlanAggregatesEveryErrorAndSavesNothing() {
        CanvasGraphCommandContext context = context("op-plan-bad");
        Map<String, Object> graph = emptyGraphMap();
        stubGraph(graph, 2, 3);

        CanvasGraphCommandException error = assertThrows(CanvasGraphCommandException.class, () ->
                service.applyPlan(context, Map.of(
                        "nodes", List.of(
                                Map.of("ref", "bad", "type", "notAKind"),
                                Map.of("ref", "txt", "type", "text"),
                                Map.of("ref", "g", "type", "imageGeneration", "refs", List.of("missing"))),
                        "edges", List.of(Map.of("source", "txt", "target", "txt"))), false, false));

        assertEquals("PLAN_INVALID", error.code());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) error.details().get("errors");
        List<String> codes = errors.stream().map(item -> String.valueOf(item.get("code"))).toList();
        assertTrue(codes.contains("NODE_TYPE_UNKNOWN"), "未知 kind 必须入错误列表：" + errors);
        assertTrue(codes.contains("UNKNOWN_REF"), "refs 指向不存在的 ref 必须入错误列表：" + errors);
        assertTrue(codes.contains("SELF_LOOP"), "自环边必须入错误列表：" + errors);
        verify(graphService, never()).save(anyString(), anyString(), anyMap(), anyInt());
    }

    @Test
    void applyPlanContinueOnErrorSkipsFailedItemsAndSavesTheRest() {
        CanvasGraphCommandContext context = context("op-plan-partial");
        Map<String, Object> graph = emptyGraphMap();
        stubGraph(graph, 4, 5);

        Map<String, Object> result = service.applyPlan(context, Map.of("nodes", List.of(
                Map.of("ref", "bad", "type", "notAKind"),
                Map.of("ref", "ok", "type", "text", "label", "可用节点"))), true, false);

        assertEquals(5, result.get("revision"));
        assertEquals(Boolean.TRUE, result.get("partial"));
        assertEquals(1, ((List<?>) result.get("nodeIds")).size());
        assertEquals(List.of("bad"), result.get("skipped"));
        assertFalse(((List<?>) result.get("errors")).isEmpty());
        verify(graphService, times(1)).save(eq("user-1"), eq("canvas-1"), anyMap(), eq(4));
    }

    @Test
    void applyPlanLayoutRunsInsideTheSameRevision() {
        CanvasGraphCommandContext context = context("op-plan-layout");
        Map<String, Object> graph = emptyGraphMap();
        stubGraph(graph, 9, 10);

        Map<String, Object> result = service.applyPlan(context, Map.of(
                "nodes", List.of(
                        Map.of("ref", "a", "type", "text", "label", "A",
                                "position", Map.of("x", 0, "y", 0)),
                        Map.of("ref", "b", "type", "text", "label", "B",
                                "position", Map.of("x", 5000, "y", 5000))),
                "layout", Map.of("mode", "horizontal")), false, false);

        assertEquals(10, result.get("revision"));
        verify(graphService, times(1)).save(eq("user-1"), eq("canvas-1"), anyMap(), eq(9));
        Map<String, Object> layout = map(result.get("layout"));
        assertNotNull(layout);
        assertEquals(2, ((List<?>) layout.get("positions")).size());
        @SuppressWarnings("unchecked")
        Map<String, String> refs = (Map<String, String>) result.get("refs");
        assertEquals(List.of(refs.get("b")), layout.get("movedNodeIds"), "只有 B 需要移动");
        Map<String, Object> patch = map(result.get("patch"));
        assertEquals(2, ((List<?>) patch.get("nodes")).size(), "本批次新建的节点都进 patch");
        // 布局结果必须真的落进图里（既存 bug：layout 改的是副本，静默无效）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(graphService).save(eq("user-1"), eq("canvas-1"), captor.capture(), eq(9));
        Map<String, Object> savedB = map(nodes(captor.getValue()).stream()
                .filter(item -> refs.get("b").equals(item.get("id")))
                .findFirst()
                .orElseThrow());
        assertEquals(520.0, ((Number) map(savedB.get("position")).get("x")).doubleValue(), "B 必须被移到 A 右侧");
        assertEquals(0.0, ((Number) map(savedB.get("position")).get("y")).doubleValue());
    }

    @Test
    void applyPlanPrunesUnreferencedResultSlotsWhenAsked() {
        CanvasGraphCommandContext context = context("op-plan-prune");
        Map<String, Object> graph = emptyGraphMap();
        stubGraph(graph, 1, 2);

        Map<String, Object> result = service.applyPlan(context, Map.of(
                "nodes", List.of(Map.of("ref", "g1", "type", "imageGeneration",
                        "resultSlots", Map.of("count", 2))),
                "pruneUnreferencedResultSlots", true), false, false);

        List<?> pruned = (List<?>) result.get("prunedNodeIds");
        assertEquals(2, pruned.size(), "没有任何下游连线的结果槽应被删掉");
        assertEquals(List.of(), result.get("resultSlotIds"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(graphService).save(eq("user-1"), eq("canvas-1"), captor.capture(), eq(1));
        assertEquals(1, nodes(captor.getValue()).size(), "只留生产节点");
        assertEquals(0, edges(captor.getValue()).size(), "槽位入边也要一起删");
        Map<String, Object> patch = map(result.get("patch"));
        assertEquals(pruned, patch.get("removedNodeIds"));
    }

    @Test
    void applyPlanDeduplicatesExistingEdgesInsteadOfFailing() {
        CanvasGraphCommandContext context = context("op-plan-dedupe");
        Map<String, Object> graph = graphWithStandardNodes();
        edges(graph).add(fullEdge("edge-existing", "text-1", "text", "producer", "prompt"));
        stubGraph(graph, 11, 12);

        Map<String, Object> result = service.applyPlan(context, Map.of(
                "nodes", List.of(Map.of("ref", "note-1", "type", "note", "label", "备注")),
                "edges", List.of(
                        edgeSpec("text-1", "producer", "text", "prompt"),
                        edgeSpec("producer", "image-1", "image", "origin"))), false, false);

        assertEquals(Boolean.TRUE, result.get("ok"));
        assertFalse(result.containsKey("errors"), "重复边必须幂等忽略，不是错误：" + result.get("errors"));
        List<?> edgeIds = (List<?>) result.get("edgeIds");
        assertEquals(2, edgeIds.size());
        assertTrue(edgeIds.contains("edge-existing"), "已存在的边要复用原 id：" + edgeIds);
        verify(graphService, times(1)).save(eq("user-1"), eq("canvas-1"), anyMap(), eq(11));
    }

    @Test
    void applyPlanReplaysIdempotentlyWithoutTouchingGraph() {
        CanvasGraphCommandContext context = context("op-plan-replay");
        CanvasGraphOperation operation = new CanvasGraphOperation();
        operation.setOperationKey("op-plan-replay");
        operation.setCanvasId("canvas-1");
        operation.setUserId("user-1");
        operation.setProjectId("project-1");
        operation.setRunId("run-1");
        operation.setCommandName("apply_plan");
        operation.setStatus("completed");
        operation.setResultJson(JsonUtil.toJson(Map.of(
                "ok", true, "command", "apply_plan", "revision", 21, "nodeIds", List.of("n-1"))));
        when(operationMapper.selectById("op-plan-replay")).thenReturn(operation);

        Map<String, Object> result = service.applyPlan(context, Map.of("nodes", List.of(
                Map.of("type", "text"))), false, false);

        assertTrue(Boolean.TRUE.equals(result.get("idempotentReplay")));
        assertEquals(21, result.get("revision"));
        verify(canvasService, never()).get(anyString(), anyString());
        verify(graphService, never()).save(anyString(), anyString(), anyMap(), anyInt());
    }

    // ── update_node 两种写法等价 ───────────────────────────────────────────────

    @Test
    void updateNodeAcceptsNestedDataPatchWithoutDoubleNesting() {
        CanvasGraphCommandContext context = context("op-update-nested");
        Map<String, Object> graph = emptyGraphMap();
        nodes(graph).add(node("n-1", "text", 0, 0, Map.of("label", "旧名字", "kind", "text")));
        stubGraph(graph, 1, 2);

        service.updateNode(context, "n-1", Map.of("data", Map.of("label", "新名字")), false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(graphService).save(eq("user-1"), eq("canvas-1"), captor.capture(), eq(1));
        Map<String, Object> data = map(nodes(captor.getValue()).getFirst().get("data"));
        assertEquals("新名字", data.get("label"), "嵌套 {data:{label}} 必须 merge 进同一层 data");
        assertFalse(data.containsKey("data"), "不得写成 data.data.label：" + data);
    }

    @Test
    void updateNodeAcceptsFlatPatchAndOmitsUntouchedFields() {
        CanvasGraphCommandContext context = context("op-update-flat");
        Map<String, Object> graph = emptyGraphMap();
        nodes(graph).add(node("n-1", "text", 0, 0,
                Map.of("label", "保留", "kind", "text", "prompt", "旧提示词")));
        stubGraph(graph, 2, 3);

        Map<String, Object> result = service.updateNode(context, "n-1", Map.of("mode", "text2image"), false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(graphService).save(eq("user-1"), eq("canvas-1"), captor.capture(), eq(2));
        Map<String, Object> data = map(nodes(captor.getValue()).getFirst().get("data"));
        assertEquals("text2image", data.get("mode"));
        assertEquals("保留", data.get("label"), "没传的字段不能被置空");
        assertEquals("旧提示词", data.get("prompt"));
        assertNotNull(result.get("patch"));
    }

    /** 既存 bug 回归：layout_graph 过去改的是 listOfMaps 的副本，位置其实没落库。 */
    @Test
    void layoutGraphPersistsPositionsToTheSavedGraph() {
        CanvasGraphCommandContext context = context("op-layout-persist");
        Map<String, Object> graph = emptyGraphMap();
        nodes(graph).add(node("a", "text", 0, 0, Map.of("label", "A")));
        nodes(graph).add(node("b", "text", 5000, 5000, Map.of("label", "B")));
        stubGraph(graph, 4, 5);

        Map<String, Object> result = service.layoutGraph(context, Map.of("mode", "horizontal"), false);

        assertEquals(5, result.get("revision"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(graphService).save(eq("user-1"), eq("canvas-1"), captor.capture(), eq(4));
        Map<String, Object> savedB = nodes(captor.getValue()).stream()
                .filter(item -> "b".equals(item.get("id")))
                .findFirst()
                .orElseThrow();
        assertEquals(520.0, ((Number) map(savedB.get("position")).get("x")).doubleValue());
        assertEquals(0.0, ((Number) map(savedB.get("position")).get("y")).doubleValue());
        assertEquals(List.of("b"), result.get("movedNodeIds"));
    }

    // ── 读工具瘦身与分页 ──────────────────────────────────────────────────────

    @Test
    void listNodesReturnsCompactViewByDefaultAndFullDataWithDetail() {
        CanvasGraphCommandContext context = context("op-list-nodes");
        Map<String, Object> graph = emptyGraphMap();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", "场景01");
        data.put("mode", "fullReference");
        data.put("status", "idle");
        data.put("prompt", "很长的提示词".repeat(60));
        data.put("assetId", "asset-1");
        data.put("assetUrl", "/api/files/images/x.png");
        nodes(graph).add(node("g-1", "imageGeneration", 100, 200, data));
        stubGraph(graph, 1, 1);

        Map<String, Object> compact = service.listNodes(context, null, null, null, false);
        Map<String, Object> compactNode = map(((List<?>) compact.get("nodes")).getFirst());
        assertEquals("场景01", compactNode.get("label"));
        assertEquals("fullReference", compactNode.get("mode"));
        assertEquals("asset-1", compactNode.get("assetId"));
        assertFalse(compactNode.containsKey("prompt"), "紧凑视图不得回长文本 prompt");
        assertFalse(compactNode.containsKey("assetUrl"), "紧凑视图不得回 assetUrl");
        assertEquals(1, compact.get("total"));
        assertEquals(100, compact.get("limit"));

        Map<String, Object> full = service.listNodes(context, null, null, null, true);
        Map<String, Object> fullNode = map(((List<?>) full.get("nodes")).getFirst());
        assertEquals(data.get("prompt"), map(fullNode.get("data")).get("prompt"), "detail=true 回完整 data");
    }

    @Test
    void listNodesPaginatesAndKeepsTotal() {
        CanvasGraphCommandContext context = context("op-list-page");
        Map<String, Object> graph = emptyGraphMap();
        for (int index = 0; index < 3; index++) {
            nodes(graph).add(node("n-" + index, "text", index * 10, 0, Map.of("label", "N" + index)));
        }
        stubGraph(graph, 1, 1);

        Map<String, Object> page = service.listNodes(context, null, 1, 1, false);

        assertEquals(3, page.get("total"));
        assertEquals(1, page.get("limit"));
        assertEquals(1, page.get("offset"));
        List<?> returned = (List<?>) page.get("nodes");
        assertEquals(1, returned.size());
        assertEquals("n-1", map(returned.getFirst()).get("id"));
    }

    @Test
    void listEdgesReturnsCompactViewWithPortOrderByDefault() {
        CanvasGraphCommandContext context = context("op-list-edges");
        Map<String, Object> graph = graphWithStandardNodes();
        Map<String, Object> edge = fullEdge("edge-1", "text-1", "text", "producer", "prompt");
        map(edge.get("data")).put("note", "不该出现在紧凑视图");
        edges(graph).add(edge);
        stubGraph(graph, 1, 1);

        Map<String, Object> compact = service.listEdges(context, null, null, null, false);
        Map<String, Object> compactEdge = map(((List<?>) compact.get("edges")).getFirst());
        assertEquals("edge-1", compactEdge.get("id"));
        assertEquals("text", compactEdge.get("sourceHandle"));
        assertFalse(mapOrEmpty(compactEdge.get("data")).containsKey("note"));

        Map<String, Object> full = service.listEdges(context, null, null, null, true);
        Map<String, Object> fullEdge = map(((List<?>) full.get("edges")).getFirst());
        assertTrue(mapOrEmpty(fullEdge.get("data")).containsKey("note"), "detail=true 回完整 data");
    }

    @Test
    void getStateKeepsWholeCanvasCountsWhilePagingCompactNodes() {
        CanvasGraphCommandContext context = context("op-get-state");
        Map<String, Object> graph = graphWithStandardNodes();
        stubGraph(graph, 1, 1);

        Map<String, Object> state = service.getState(context, 1, 0, true, false);

        assertEquals(3, state.get("nodeCount"), "nodeCount 仍是整张画布口径");
        assertEquals(1, state.get("returnedNodeCount"));
        Map<String, Object> returnedGraph = map(state.get("graph"));
        assertEquals(1, ((List<?>) returnedGraph.get("nodes")).size());
        assertFalse(map(((List<?>) returnedGraph.get("nodes")).getFirst()).containsKey("prompt"));
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

    /** text 提示词 → textToImage 生产节点 → image 结果占位。 */
    private static Map<String, Object> graphWithStandardNodes() {
        Map<String, Object> graph = emptyGraphMap();
        nodes(graph).add(node("text-1", "text", 0, 0, Map.of("label", "提示词")));
        nodes(graph).add(node("producer", "textToImage", 600, 0, Map.of("label", "文生图")));
        nodes(graph).add(node("image-1", "image", 1200, 0, Map.of("label", "结果 1")));
        return graph;
    }

    private static Map<String, Object> nodeSpec(String type, String label, Map<String, Object> resultSlots) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("type", type);
        spec.put("label", label);
        spec.put("resultSlots", resultSlots);
        return spec;
    }

    private static Map<String, Object> edgeSpec(String source, String target, String sourceHandle, String targetHandle) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("source", source);
        spec.put("target", target);
        spec.put("sourceHandle", sourceHandle);
        spec.put("targetHandle", targetHandle);
        return spec;
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

    private static Map<String, Object> fullEdge(String id, String source, String sourceHandle,
                                                String target, String targetHandle) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", id);
        edge.put("source", source);
        edge.put("sourceHandle", sourceHandle);
        edge.put("target", target);
        edge.put("targetHandle", targetHandle);
        edge.put("type", "canvas");
        edge.put("data", new LinkedHashMap<>(Map.of("portOrder", 0)));
        return edge;
    }

    private static List<String> slotLabels(Map<String, Object> graph) {
        return nodes(graph).stream()
                .filter(item -> String.valueOf(mapOrEmpty(item.get("data")).get("label")).startsWith("结果 "))
                .map(item -> String.valueOf(mapOrEmpty(item.get("data")).get("label")))
                .toList();
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
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOrEmpty(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
