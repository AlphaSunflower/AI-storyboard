package com.moon.xiaoyuecanvas.service;

import com.moon.moonagent.entity.MorrowCanvas;
import com.moon.moonagent.security.FileUrlSigner;
import com.moon.moonagent.service.FileStorageService;
import com.moon.moonagent.service.MorrowCanvasService;
import com.moon.xiaoyuecanvas.dto.response.CanvasGraphResponse;
import com.moon.xiaoyuecanvas.entity.CanvasAsset;
import com.moon.xiaoyuecanvas.mapper.CanvasAssetMapper;
import com.moon.xiaoyuecanvas.mapper.CanvasGraphOperationMapper;
import com.moon.xiaoyuecanvas.service.impl.CanvasGraphCommandServiceImpl;
import com.storyboard.asset.client.AssetServiceException;
import com.storyboard.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * apply_plan 的内联素材（plan.nodes[].media）：一次调用同时落素材节点 + 生成节点 + refs 连线 +
 * 结果槽 + 布局，只 +1 revision；同一份素材只建一个节点；非法素材进错误汇总且不落库。
 */
class CanvasGraphApplyPlanInlineMediaTest {

    private CanvasGraphService graphService;
    private CanvasGraphOperationMapper operationMapper;
    private MorrowCanvasService canvasService;
    private CanvasAssetMapper assetMapper;
    private CanvasProjectLibraryService libraryService;
    private FileStorageService fileStorageService;
    private CanvasGraphCommandService service;

    @BeforeEach
    void setUp() {
        graphService = mock(CanvasGraphService.class);
        operationMapper = mock(CanvasGraphOperationMapper.class);
        canvasService = mock(MorrowCanvasService.class);
        assetMapper = mock(CanvasAssetMapper.class);
        libraryService = mock(CanvasProjectLibraryService.class);
        fileStorageService = mock(FileStorageService.class);
        service = new CanvasGraphCommandServiceImpl(
                graphService,
                operationMapper,
                canvasService,
                mock(CanvasAssetService.class),
                assetMapper,
                libraryService,
                fileStorageService,
                new FileUrlSigner("test-secret", 3600),
                "http://localhost:8082");
    }

    @Test
    void inlineMediaLandsNodeAssetRefsSlotsAndLayoutInOneRevision() {
        CanvasGraphCommandContext context = context("op-inline-media");
        Map<String, Object> graph = emptyGraphMap();
        stubGraph(graph, 3, 4);
        when(libraryService.importMedia(eq("user-1"), eq("canvas-1"), eq("media-1"), anyString()))
                .thenReturn(asset("asset-media-1", "image", "image/png", "/api/files/images/a.png"));

        Map<String, Object> result = service.applyPlan(context, Map.of(
                "nodes", List.of(
                        Map.of("ref", "m1", "position", Map.of("x", 0, "y", 0),
                                "media", Map.of("mediaId", "media-1", "name", "a.png", "label", "参考图")),
                        Map.of("ref", "g1", "type", "imageGeneration", "label", "场景01",
                                "refs", List.of("m1"), "resultSlots", Map.of("count", 1))),
                "layout", Map.of("mode", "dag")), false, false);

        // 硬约束：一次 apply_plan = 一次 saveGraph，revision 只 +1
        assertEquals(4, result.get("revision"));
        assertEquals(List.of("asset-media-1"), result.get("assetIds"));

        @SuppressWarnings("unchecked")
        Map<String, String> refs = (Map<String, String>) result.get("refs");
        assertEquals(2, refs.size(), "素材节点与生成节点都要有 ref 映射：" + refs);

        // 素材节点本身必须带着资产落库（同一份素材 = 一个媒体节点 + 一条 canvas_assets 行）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(graphService, times(1)).save(eq("user-1"), eq("canvas-1"), captor.capture(), eq(3));
        Map<String, Object> saved = captor.getValue();
        Map<String, Object> savedMedia = savedNode(saved, "asset-media-1");
        assertNotNull(savedMedia, "保存的图里必须有素材节点");
        assertEquals("image", savedMedia.get("type"), "素材节点类型按素材判定 image/video");
        assertEquals("参考图", map(savedMedia.get("data")).get("label"));

        // 素材节点 → 生成节点：端口由端口表自动选择（image → reference）
        Map<String, Object> toProducer = edges(saved).stream()
                .filter(edge -> refs.get("m1").equals(edge.get("source"))
                        && refs.get("g1").equals(edge.get("target")))
                .findFirst()
                .orElseThrow();
        assertEquals("image", toProducer.get("sourceHandle"));
        assertEquals("reference", toProducer.get("targetHandle"));

        // patch.nodes 必须含素材节点（前端据此做本地增量应用，不整图重载）
        Map<String, Object> patch = map(result.get("patch"));
        assertTrue(((List<?>) patch.get("nodes")).stream()
                        .map(CanvasGraphApplyPlanInlineMediaTest::map)
                        .anyMatch(node -> refs.get("m1").equals(node.get("id"))),
                "patch.nodes 必须包含本次导入的素材节点");
        assertNotNull(result.get("layout"), "布局在同一次保存内完成");
    }

    @Test
    void repeatedMediaIdInOnePlanCreatesASingleNode() {
        CanvasGraphCommandContext context = context("op-inline-dedupe");
        Map<String, Object> graph = emptyGraphMap();
        stubGraph(graph, 7, 8);
        when(libraryService.importMedia(eq("user-1"), eq("canvas-1"), eq("media-7"), anyString()))
                .thenReturn(asset("asset-7", "image", "image/png", "/api/files/images/7.png"));

        Map<String, Object> result = service.applyPlan(context, Map.of("nodes", List.of(
                Map.of("ref", "m1", "media", Map.of("mediaId", "media-7")),
                Map.of("ref", "m2", "media", Map.of("mediaId", "media-7")),
                Map.of("ref", "g1", "type", "imageGeneration", "refs", List.of("m1", "m2")))), false, false);

        assertEquals(8, result.get("revision"));
        // 同一 mediaId 只导入一次，只产生一行 canvas_assets
        verify(libraryService, times(1)).importMedia(eq("user-1"), eq("canvas-1"), eq("media-7"), anyString());
        assertEquals(1, ((List<?>) result.get("assetIds")).size());

        @SuppressWarnings("unchecked")
        Map<String, String> refs = (Map<String, String>) result.get("refs");
        assertEquals(refs.get("m1"), refs.get("m2"), "重复出现的素材 ref 必须复用同一个节点 id");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(graphService, times(1)).save(eq("user-1"), eq("canvas-1"), captor.capture(), eq(7));
        assertEquals(2, nodes(captor.getValue()).size(), "1 个素材节点 + 1 个生成节点，不重复建素材节点");
        assertEquals(1, edges(captor.getValue()).size(), "两个 ref 指向同一素材节点，同一条边只建一次");
        assertEquals(2, ((List<?>) map(result.get("patch")).get("nodes")).size(), "patch 不出现重复素材节点");
    }

    @Test
    void inlineMediaFromFilesUrlIsStoredAsCanvasAssetWithoutDownloading() {
        CanvasGraphCommandContext context = context("op-inline-url");
        Map<String, Object> graph = emptyGraphMap();
        stubGraph(graph, 0, 1);
        when(assetMapper.selectOne(any())).thenReturn(null);

        Map<String, Object> result = service.applyPlan(context, Map.of("nodes", List.of(
                Map.of("ref", "m1", "media", Map.of(
                        "url", "/api/files/images/inline.png", "name", "inline.png")),
                Map.of("ref", "t1", "type", "text", "label", "提示词"))), false, false);

        assertEquals(1, result.get("revision"));
        // 相对 /api/files/** 直接登记为画布资产，不再二次下载（与 import_media_batch 同一条路径）
        verify(fileStorageService, never()).saveImage(anyString());
        ArgumentCaptor<CanvasAsset> assetCaptor = ArgumentCaptor.forClass(CanvasAsset.class);
        verify(assetMapper, times(1)).insert(assetCaptor.capture());
        assertEquals("/api/files/images/inline.png", assetCaptor.getValue().getObjectUrl());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(graphService, times(1)).save(eq("user-1"), eq("canvas-1"), captor.capture(), eq(0));
        Map<String, Object> savedMedia = savedNode(captor.getValue(), assetCaptor.getValue().getId());
        assertNotNull(savedMedia);
        assertEquals("image", savedMedia.get("type"));
        assertEquals("/api/files/images/inline.png", map(savedMedia.get("data")).get("assetUrl"));
    }

    @Test
    void invalidInlineMediaIsAggregatedAndNothingIsSaved() {
        CanvasGraphCommandContext context = context("op-inline-bad");
        Map<String, Object> graph = emptyGraphMap();
        stubGraph(graph, 2, 3);
        when(libraryService.importMedia(eq("user-1"), eq("canvas-1"), eq("missing"), anyString()))
                .thenThrow(new AssetServiceException(40401, 404, "素材不存在", "corr-1"));

        CanvasGraphCommandException error = assertThrows(CanvasGraphCommandException.class, () ->
                service.applyPlan(context, Map.of("nodes", List.of(
                        // mediaId 与 url 二选一，两个都给
                        Map.of("ref", "bad1", "media", Map.of(
                                "mediaId", "m-1", "url", "/api/files/images/a.png")),
                        // 两个都不给
                        Map.of("ref", "bad2", "media", Map.of("name", "a.png")),
                        // mediaId 找不到（asset-service 以 AssetServiceException 抛出）
                        Map.of("ref", "bad3", "media", Map.of("mediaId", "missing", "name", "x.png")),
                        // 素材节点不是生产节点，不能声明 resultSlots
                        Map.of("ref", "bad4", "media", Map.of("mediaId", "m-2"),
                                "resultSlots", Map.of("count", 1)),
                        Map.of("ref", "ok", "type", "text", "label", "可用节点"))), false, false));

        assertEquals("PLAN_INVALID", error.code());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) error.details().get("errors");
        assertEquals(4, errors.size(), "全部非法素材必须一次性汇总：" + errors);
        Map<String, String> codeByRef = new LinkedHashMap<>();
        for (Map<String, Object> item : errors) {
            codeByRef.put(String.valueOf(item.get("ref")), String.valueOf(item.get("code")));
        }
        assertEquals(Map.of(
                "bad1", "INVALID_ARGUMENTS",
                "bad2", "INVALID_ARGUMENTS",
                "bad3", "MEDIA_IMPORT_FAILED",
                "bad4", "INVALID_ARGUMENTS"), codeByRef, "每个失败项的 ref 与错误码：" + errors);
        Map<String, Object> missing = errors.stream()
                .filter(item -> "bad3".equals(item.get("ref")))
                .findFirst()
                .orElseThrow();
        assertTrue(String.valueOf(missing.get("message")).contains("素材不存在"),
                "找不到的 mediaId 必须带上可读原因：" + missing);

        // 整批不落库
        verify(graphService, never()).save(anyString(), anyString(), anyMap(), anyInt());
        verify(libraryService, times(1))
                .importMedia(eq("user-1"), eq("canvas-1"), eq("missing"), anyString());
        verify(libraryService, never()).importMedia(anyString(), anyString(), eq("m-1"), anyString());
        verify(assetMapper, never()).insert(any(CanvasAsset.class));
    }

    @Test
    void inlineMediaRespectsContinueOnErrorAndSavesTheSuccessfulItems() {
        CanvasGraphCommandContext context = context("op-inline-partial");
        Map<String, Object> graph = emptyGraphMap();
        stubGraph(graph, 4, 5);
        when(libraryService.importMedia(eq("user-1"), eq("canvas-1"), eq("media-ok"), anyString()))
                .thenReturn(asset("asset-ok", "image", "image/png", "/api/files/images/ok.png"));
        when(libraryService.importMedia(eq("user-1"), eq("canvas-1"), eq("media-missing"), anyString()))
                .thenThrow(new BusinessException(40401, "项目库素材不存在或没有媒体文件"));

        Map<String, Object> result = service.applyPlan(context, Map.of("nodes", List.of(
                Map.of("ref", "m1", "media", Map.of("mediaId", "media-ok")),
                Map.of("ref", "m2", "media", Map.of("mediaId", "media-missing")),
                Map.of("ref", "g1", "type", "imageGeneration", "refs", List.of("m2")))), true, false);

        assertEquals(5, result.get("revision"));
        assertEquals(Boolean.TRUE, result.get("partial"));
        assertEquals(List.of("asset-ok"), result.get("assetIds"));
        assertTrue(((List<?>) result.get("skipped")).contains("m2"), "失败项要进 skipped：" + result.get("skipped"));
        List<String> codes = ((List<?>) result.get("errors")).stream()
                .map(item -> String.valueOf(map(item).get("code")))
                .toList();
        assertTrue(codes.contains("MEDIA_IMPORT_FAILED"), "素材导入失败要入 errors：" + codes);
        assertTrue(codes.contains("DEPENDENCY_FAILED"), "依赖失败素材的连线要入 errors：" + codes);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(graphService, times(1)).save(eq("user-1"), eq("canvas-1"), captor.capture(), eq(4));
        assertNotNull(savedNode(captor.getValue(), "asset-ok"), "成功素材必须落库");
        assertEquals(2, nodes(captor.getValue()).size(), "只落素材节点 + 生成节点，失败的素材不建节点");
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

    private static CanvasAsset asset(String id, String type, String mimeType, String objectUrl) {
        CanvasAsset asset = new CanvasAsset();
        asset.setId(id);
        asset.setCanvasId("canvas-1");
        asset.setProjectId("project-1");
        asset.setUserId("user-1");
        asset.setType(type);
        asset.setMimeType(mimeType);
        asset.setObjectUrl(objectUrl);
        asset.setObjectKey(objectUrl.substring("/api/files/".length()));
        asset.setStatus("ready");
        return asset;
    }

    /** 内存图里 assetId 匹配的素材节点（stubGraph 把保存的图对象直接回传，因此断言的是真正落库的内容）。 */
    private static Map<String, Object> savedNode(Map<String, Object> graph, String assetId) {
        return nodes(graph).stream()
                .filter(node -> assetId.equals(mapOrEmpty(node.get("data")).get("assetId")))
                .findFirst()
                .orElse(null);
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
