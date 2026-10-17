package com.moon.xiaoyuecanvas.service;

import com.moon.moonagent.entity.MorrowCanvas;
import com.moon.moonagent.service.FileStorageService;
import com.moon.moonagent.service.MorrowCanvasService;
import com.moon.xiaoyuecanvas.dto.response.CanvasGraphResponse;
import com.moon.xiaoyuecanvas.entity.CanvasAsset;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CanvasGraphCommandServiceImplTest {

    private CanvasGraphService graphService;
    private CanvasGraphOperationMapper operationMapper;
    private MorrowCanvasService canvasService;
    private CanvasAssetService assetService;
    private CanvasAssetMapper assetMapper;
    private CanvasProjectLibraryService libraryService;
    private FileStorageService fileStorageService;
    private CanvasGraphCommandService service;

    @BeforeEach
    void setUp() {
        graphService = mock(CanvasGraphService.class);
        operationMapper = mock(CanvasGraphOperationMapper.class);
        canvasService = mock(MorrowCanvasService.class);
        assetService = mock(CanvasAssetService.class);
        assetMapper = mock(CanvasAssetMapper.class);
        libraryService = mock(CanvasProjectLibraryService.class);
        fileStorageService = mock(FileStorageService.class);
        service = new CanvasGraphCommandServiceImpl(
                graphService,
                operationMapper,
                canvasService,
                assetService,
                assetMapper,
                libraryService,
                fileStorageService,
                new com.moon.moonagent.security.FileUrlSigner("test-secret", 3600),
                "http://localhost:8082");
    }

    @Test
    void createNodeUsesLoadModifySaveCasAndReturnsNode() {
        CanvasGraphCommandContext context = context("op-create-node");
        when(canvasService.get("user-1", "canvas-1")).thenReturn(canvas());
        when(graphService.load("user-1", "canvas-1")).thenReturn(emptyGraph());
        when(operationMapper.selectById("op-create-node")).thenReturn(null);
        when(operationMapper.claim(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);
        when(graphService.save(eq("user-1"), eq("canvas-1"), anyMap(), eq(0)))
                .thenAnswer(invocation -> {
                    Map<String, Object> saved = invocation.getArgument(2);
                    return new CanvasGraphResponse(saved, 1, "2026-09-30T00:00:00Z");
                });

        Map<String, Object> result = service.createNode(context, Map.of(
                "id", "node-text",
                "type", "text",
                "position", Map.of("x", 12, "y", 24),
                "data", Map.of("label", "雪茄房说明", "prompt", "复古木质空间")), false);

        assertEquals("node-text", result.get("nodeId"));
        assertEquals(1, result.get("revision"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> graphCaptor = ArgumentCaptor.forClass(Map.class);
        verify(graphService).save(eq("user-1"), eq("canvas-1"), graphCaptor.capture(), eq(0));
        List<Map<String, Object>> nodes = nodes(graphCaptor.getValue());
        assertEquals(1, nodes.size());
        assertEquals("text", nodes.getFirst().get("type"));
        assertEquals("雪茄房说明", map(nodes.getFirst().get("data")).get("label"));
        verify(operationMapper).complete(eq("op-create-node"), anyString(), anyString(), anyString());
    }

    @Test
    void createEdgeRejectsMediaMismatchBeforeSaving() {
        CanvasGraphCommandContext context = context("op-bad-edge");
        Map<String, Object> graph = emptyGraphMap();
        nodes(graph).add(node("text-1", "text", 0, 0, Map.of("label", "提示词")));
        nodes(graph).add(node("video-1", "videoMerge", 420, 0, Map.of("label", "合并")));
        when(canvasService.get("user-1", "canvas-1")).thenReturn(canvas());
        when(graphService.load("user-1", "canvas-1"))
                .thenReturn(new CanvasGraphResponse(graph, 3, "2026-09-30T00:00:00Z"));
        when(operationMapper.selectById("op-bad-edge")).thenReturn(null);
        when(operationMapper.claim(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);

        CanvasGraphCommandException error = assertThrows(CanvasGraphCommandException.class, () ->
                service.createEdge(context, Map.of(
                        "source", "text-1",
                        "target", "video-1",
                        "sourceHandle", "text",
                        "targetHandle", "clips"), false));

        assertEquals("PORT_KIND_MISMATCH", error.code());
        verify(graphService, never()).save(anyString(), anyString(), anyMap(), anyInt());
    }

    @Test
    void importMediaWithCanvasRelativeFilesUrlCreatesDurableCanvasAssetAndNode() {
        CanvasGraphCommandContext context = context("op-import");
        when(canvasService.get("user-1", "canvas-1")).thenReturn(canvas());
        when(graphService.load("user-1", "canvas-1")).thenReturn(emptyGraph());
        when(operationMapper.selectById("op-import")).thenReturn(null);
        when(operationMapper.claim(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);
        when(assetMapper.selectOne(any())).thenReturn(null);
        when(graphService.save(eq("user-1"), eq("canvas-1"), anyMap(), eq(0)))
                .thenAnswer(invocation -> new CanvasGraphResponse(invocation.getArgument(2), 1, null));

        Map<String, Object> result = service.importMedia(context, Map.of(
                "kind", "image",
                "url", "/api/files/images/reference.png",
                "name", "reference.png",
                "label", "参考图",
                "position", Map.of("x", 100, "y", 120)), false);

        assertFalse(Boolean.FALSE.equals(result.get("ok")));
        ArgumentCaptor<CanvasAsset> assetCaptor = ArgumentCaptor.forClass(CanvasAsset.class);
        verify(assetMapper).insert(assetCaptor.capture());
        assertEquals("/api/files/images/reference.png", assetCaptor.getValue().getObjectUrl());
        assertEquals("images/reference.png", assetCaptor.getValue().getObjectKey());
        assertEquals("reference.png", assetCaptor.getValue().getMimeType().contains("png") ? "reference.png" : "");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> graphCaptor = ArgumentCaptor.forClass(Map.class);
        verify(graphService).save(eq("user-1"), eq("canvas-1"), graphCaptor.capture(), eq(0));
        Map<String, Object> imported = nodes(graphCaptor.getValue()).getFirst();
        assertEquals("image", imported.get("type"));
        assertEquals(assetCaptor.getValue().getId(), map(imported.get("data")).get("assetId"));
    }

    @Test
    void importMediaUsesLibraryMediaIdInsteadOfFetchingSignedUrl() {
        CanvasGraphCommandContext context = context("op-import-library");
        when(canvasService.get("user-1", "canvas-1")).thenReturn(canvas());
        when(graphService.load("user-1", "canvas-1")).thenReturn(emptyGraph());
        when(operationMapper.selectById("op-import-library")).thenReturn(null);
        when(operationMapper.claim(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);
        CanvasAsset asset = new CanvasAsset();
        asset.setId("asset-library");
        asset.setCanvasId("canvas-1");
        asset.setType("image");
        asset.setMimeType("image/png");
        asset.setObjectUrl("/api/files/images/library.png");
        asset.setObjectKey("images/library.png");
        when(libraryService.importMedia(
                eq("user-1"), eq("canvas-1"), eq("media-9"), anyString()))
                .thenReturn(asset);
        when(graphService.save(eq("user-1"), eq("canvas-1"), anyMap(), eq(0)))
                .thenAnswer(invocation -> new CanvasGraphResponse(invocation.getArgument(2), 1, null));

        Map<String, Object> result = service.importMedia(context, Map.of(
                "kind", "image",
                "url", "/api/library/media/media-9/content?token=expired",
                "name", "library.png",
                "label", "项目库参考图"), false);

        assertFalse(Boolean.FALSE.equals(result.get("ok")));
        verify(libraryService).importMedia(
                eq("user-1"), eq("canvas-1"), eq("media-9"), anyString());
        verify(fileStorageService, never()).saveImage(anyString());
        verify(assetMapper, never()).insert(any(CanvasAsset.class));
    }

    @Test
    void batchImportUsesOneGraphRevisionForAllMedia() {
        CanvasGraphCommandContext context = context("op-import-batch");
        when(canvasService.get("user-1", "canvas-1")).thenReturn(canvas());
        when(graphService.load("user-1", "canvas-1")).thenReturn(emptyGraph());
        when(operationMapper.selectById("op-import-batch")).thenReturn(null);
        when(operationMapper.claim(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);
        when(assetMapper.selectOne(any())).thenReturn(null);
        when(graphService.save(eq("user-1"), eq("canvas-1"), anyMap(), eq(0)))
                .thenAnswer(invocation -> new CanvasGraphResponse(invocation.getArgument(2), 1, null));

        Map<String, Object> result = service.importMediaBatch(context, List.of(
                Map.of("kind", "image", "url", "/api/files/images/a.png", "name", "a.png"),
                Map.of("kind", "image", "url", "/api/files/images/b.png", "name", "b.png")), false);

        assertFalse(Boolean.FALSE.equals(result.get("ok")));
        assertEquals(2, ((List<?>) result.get("nodeIds")).size());
        verify(graphService, org.mockito.Mockito.times(1))
                .save(eq("user-1"), eq("canvas-1"), anyMap(), eq(0));
        verify(assetMapper, org.mockito.Mockito.times(2)).insert(any(CanvasAsset.class));
    }

    @Test
    void completedOperationReplaysStoredResultWithoutTouchingGraph() {
        CanvasGraphCommandContext context = context("op-replay");
        CanvasGraphOperation operation = new CanvasGraphOperation();
        operation.setOperationKey("op-replay");
        operation.setCanvasId("canvas-1");
        operation.setUserId("user-1");
        operation.setProjectId("project-1");
        operation.setRunId("run-1");
        operation.setCommandName("create_node");
        operation.setStatus("completed");
        operation.setResultJson(JsonUtil.toJson(Map.of(
                "ok", true,
                "nodeId", "node-existing",
                "revision", 7)));
        when(operationMapper.selectById("op-replay")).thenReturn(operation);

        Map<String, Object> result = service.createNode(context, Map.of(
                "id", "node-new",
                "type", "text"), false);

        assertTrue(Boolean.TRUE.equals(result.get("idempotentReplay")));
        assertEquals("node-existing", result.get("nodeId"));
        verify(canvasService, never()).get(anyString(), anyString());
        verify(graphService, never()).load(anyString(), anyString());
        verify(graphService, never()).save(anyString(), anyString(), anyMap(), anyInt());
    }

    @Test
    void projectOwnershipMismatchIsRejected() {
        CanvasGraphCommandContext context = context("op-owner");
        MorrowCanvas canvas = canvas();
        canvas.setProjectId("project-other");
        when(canvasService.get("user-1", "canvas-1")).thenReturn(canvas);

        CanvasGraphCommandException error = assertThrows(CanvasGraphCommandException.class, () ->
                service.getState(context, null, null, true, false));

        assertEquals("CANVAS_PROJECT_MISMATCH", error.code());
    }

    @Test
    void createResultSlotsCreatesOrderedPlaceholdersAndEdgesInOneRevision() {
        CanvasGraphCommandContext context = context("op-slots");
        Map<String, Object> graph = emptyGraphMap();
        nodes(graph).add(node("producer", "textToImage", 100, 100, Map.of("label", "文生图")));
        when(canvasService.get("user-1", "canvas-1")).thenReturn(canvas());
        when(graphService.load("user-1", "canvas-1"))
                .thenReturn(new CanvasGraphResponse(graph, 5, null));
        when(operationMapper.selectById("op-slots")).thenReturn(null);
        when(operationMapper.claim(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);
        when(graphService.save(eq("user-1"), eq("canvas-1"), anyMap(), eq(5)))
                .thenAnswer(invocation -> new CanvasGraphResponse(invocation.getArgument(2), 6, null));

        Map<String, Object> result = service.createResultSlots(context, Map.of(
                "producerNodeId", "producer",
                "kind", "image",
                "count", 3), false);

        assertEquals(6, result.get("revision"));
        assertEquals(3, ((List<?>) result.get("nodeIds")).size());
        assertEquals(3, ((List<?>) result.get("edgeIds")).size());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> graphCaptor = ArgumentCaptor.forClass(Map.class);
        verify(graphService).save(eq("user-1"), eq("canvas-1"), graphCaptor.capture(), eq(5));
        assertEquals(4, nodes(graphCaptor.getValue()).size());
        assertEquals(3, ((List<?>) graphCaptor.getValue().get("edges")).size());
    }

    @Test
    void createResultSlotsContinuesCanvasWideNumberingAcrossProducers() {
        CanvasGraphCommandContext context = context("op-slots-2");
        Map<String, Object> graph = emptyGraphMap();
        nodes(graph).add(node("producer-a", "textToImage", 100, 100, Map.of("label", "文生图")));
        nodes(graph).add(node("slot-existing", "image", 700, 100, Map.of("label", "结果 1")));
        nodes(graph).add(node("producer-b", "imageToImage", 100, 500, Map.of("label", "图生图")));
        when(canvasService.get("user-1", "canvas-1")).thenReturn(canvas());
        when(graphService.load("user-1", "canvas-1"))
                .thenReturn(new CanvasGraphResponse(graph, 7, null));
        when(operationMapper.selectById("op-slots-2")).thenReturn(null);
        when(operationMapper.claim(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);
        when(graphService.save(eq("user-1"), eq("canvas-1"), anyMap(), eq(7)))
                .thenAnswer(invocation -> new CanvasGraphResponse(invocation.getArgument(2), 8, null));

        service.createResultSlots(context, Map.of(
                "producerNodeId", "producer-b",
                "kind", "image",
                "count", 2), false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> graphCaptor = ArgumentCaptor.forClass(Map.class);
        verify(graphService).save(eq("user-1"), eq("canvas-1"), graphCaptor.capture(), eq(7));
        List<String> labels = nodes(graphCaptor.getValue()).stream()
                .filter(item -> "producer-b".equals(
                        ((Map<?, ?>) item.getOrDefault("data", Map.of())).get("producerNodeId")))
                .map(item -> String.valueOf(((Map<?, ?>) item.get("data")).get("label")))
                .toList();
        // 每个生产节点各自从「结果 1」重来会让画布上出现多个同名槽位
        assertEquals(List.of("结果 2", "结果 3"), labels);
    }

    @Test
    void layoutGraphWritesDagPositionsAndRevision() {
        CanvasGraphCommandContext context = context("op-layout");
        Map<String, Object> graph = emptyGraphMap();
        nodes(graph).add(node("text", "text", 900, 700, Map.of("label", "提示词")));
        nodes(graph).add(node("image", "textToImage", 1_100, 700, Map.of("label", "文生图")));
        nodes(graph).add(node("output", "image", 1_300, 700, Map.of("label", "结果")));
        ((List<Map<String, Object>>) graph.get("edges")).add(edge("text", "image", "text", "prompt"));
        ((List<Map<String, Object>>) graph.get("edges")).add(edge("image", "output", "image", "origin"));
        when(canvasService.get("user-1", "canvas-1")).thenReturn(canvas());
        when(graphService.load("user-1", "canvas-1"))
                .thenReturn(new CanvasGraphResponse(graph, 9, null));
        when(operationMapper.selectById("op-layout")).thenReturn(null);
        when(operationMapper.claim(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);
        when(graphService.save(eq("user-1"), eq("canvas-1"), anyMap(), eq(9)))
                .thenAnswer(invocation -> new CanvasGraphResponse(invocation.getArgument(2), 10, null));

        Map<String, Object> result = service.layoutGraph(context, Map.of(
                "mode", "dag",
                "anchor", Map.of("x", 0, "y", 0)), false);

        assertEquals(10, result.get("revision"));
        assertEquals(3, ((List<?>) result.get("movedNodeIds")).size());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> graphCaptor = ArgumentCaptor.forClass(Map.class);
        verify(graphService).save(eq("user-1"), eq("canvas-1"), graphCaptor.capture(), eq(9));
        List<Map<String, Object>> laidOut = nodes(graphCaptor.getValue());
        double textX = ((Number) map(laidOut.get(0).get("position")).get("x")).doubleValue();
        double imageX = ((Number) map(laidOut.get(1).get("position")).get("x")).doubleValue();
        double outputX = ((Number) map(laidOut.get(2).get("position")).get("x")).doubleValue();
        assertTrue(textX < imageX && imageX < outputX);
    }

    private CanvasGraphCommandContext context(String operationKey) {
        return new CanvasGraphCommandContext(
                "user-1", "project-1", "canvas-1", "run-1", operationKey);
    }

    private MorrowCanvas canvas() {
        MorrowCanvas canvas = new MorrowCanvas();
        canvas.setId("canvas-1");
        canvas.setProjectId("project-1");
        canvas.setTitle("雪茄房画布");
        return canvas;
    }

    private CanvasGraphResponse emptyGraph() {
        return new CanvasGraphResponse(emptyGraphMap(), 0, null);
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

    private static Map<String, Object> node(
            String id,
            String type,
            double x,
            double y,
            Map<String, Object> data
    ) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", type);
        node.put("position", new LinkedHashMap<>(Map.of("x", x, "y", y)));
        node.put("width", 320);
        node.put("height", 240);
        node.put("data", new LinkedHashMap<>(data));
        return node;
    }

    private static Map<String, Object> edge(
            String source,
            String target,
            String sourceHandle,
            String targetHandle
    ) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", "edge-" + source + "-" + target);
        edge.put("source", source);
        edge.put("sourceHandle", sourceHandle);
        edge.put("target", target);
        edge.put("targetHandle", targetHandle);
        edge.put("type", "canvas");
        edge.put("data", new LinkedHashMap<>());
        return edge;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nodes(Map<String, Object> graph) {
        return (List<Map<String, Object>>) graph.get("nodes");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
