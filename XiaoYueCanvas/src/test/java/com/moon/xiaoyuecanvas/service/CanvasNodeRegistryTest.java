package com.moon.xiaoyuecanvas.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务端节点目录必须镜像画布前端当前的节点契约。
 *
 * <p>前端已把生成节点合并为 {@code imageGeneration} / {@code videoGeneration}
 * （端口 {@code prompt} / {@code reference} / {@code video}），服务端若还停留在旧 kind，
 * 模型建出来的节点与用户画布上的节点类型不一致：连线会报
 * {@code NODE_TYPE_UNKNOWN: videoGeneration}，旧端口名也会被前端归并导致边挂不上。
 */
class CanvasNodeRegistryTest {

    @Test
    void canonicalGenerationKindsAreResolvable() {
        // 回归：agent 在已有画布上连线时曾报「未知画布节点类型: videoGeneration」
        assertNotNull(CanvasNodeRegistry.get("imageGeneration"));
        assertNotNull(CanvasNodeRegistry.get("videoGeneration"));

        CanvasNodeRegistry.NodeType video = CanvasNodeRegistry.require("videoGeneration");
        assertEquals(List.of("prompt", "reference", "video"),
                video.inputs().stream().map(CanvasNodeRegistry.Port::id).toList());
        CanvasNodeRegistry.Port reference = video.inputs().stream()
                .filter(port -> "reference".equals(port.id())).findFirst().orElseThrow();
        assertTrue(reference.ordered(), "参考图端口按 portOrder 排序（首尾帧靠它区分顺序）");
        assertTrue(reference.max() > 1, "合并后多张参考图走同一个 reference 端口");

        CanvasNodeRegistry.NodeType image = CanvasNodeRegistry.require("imageGeneration");
        assertEquals(List.of("prompt", "reference"),
                image.inputs().stream().map(CanvasNodeRegistry.Port::id).toList());
    }

    @Test
    void catalogHidesLegacyKindsButStillValidatesThem() {
        List<String> catalog = CanvasNodeRegistry.list().stream()
                .map(CanvasNodeRegistry.NodeType::kind).toList();
        assertTrue(catalog.contains("imageGeneration"));
        assertTrue(catalog.contains("videoGeneration"));
        // 分镜链路（storyboardPlanner → shot）也必须可创建，否则 skill 教的链路会报未知类型
        assertTrue(catalog.contains("storyboardPlanner"));
        assertTrue(catalog.contains("shot"));
        // 模型不应该再被教着创建旧 kind
        assertFalse(catalog.contains("textToImage"));
        assertFalse(catalog.contains("firstLastFrameToVideo"));

        // 但存量画布里的旧节点必须仍能通过连线校验
        assertNotNull(CanvasNodeRegistry.get("textToImage"));
        assertNotNull(CanvasNodeRegistry.get("firstLastFrameToVideo"));
        assertNotNull(CanvasNodeRegistry.get("imageToVideo"));
    }

    @Test
    void placeholderNodesAcceptMergedProducers() {
        var imageOrigin = CanvasNodeRegistry.require("image").inputs().getFirst().acceptsKinds();
        assertTrue(imageOrigin.contains("imageGeneration"), "结果槽要能接合并后的图片生成节点");
        assertTrue(imageOrigin.contains("textToImage"), "存量旧图片节点仍能接结果槽");

        var videoOrigin = CanvasNodeRegistry.require("video").inputs().getFirst().acceptsKinds();
        assertTrue(videoOrigin.contains("videoGeneration"), "结果槽要能接合并后的视频生成节点");
        assertTrue(videoOrigin.contains("firstLastFrameToVideo"), "存量旧视频节点仍能接结果槽");

        assertTrue(CanvasNodeRegistry.require("videoMerge").inputs().getFirst().acceptsKinds()
                .contains("videoGeneration"), "视频合并要能收合并后视频节点的产物");
    }

    @Test
    void aliasesPointAtMergedKinds() {
        assertEquals("imageGeneration", CanvasNodeRegistry.normalizeAlias("imageGen"));
        assertEquals("videoGeneration", CanvasNodeRegistry.normalizeAlias("videoGen"));
        assertEquals("text", CanvasNodeRegistry.normalizeAlias("prompt"));
    }

    @Test
    void toMapExposesPortsAndLimits() {
        Map<String, Object> mapped = CanvasNodeRegistry.require("videoGeneration").toMap();
        assertEquals("videoGeneration", mapped.get("kind"));
        assertEquals(Map.of("width", 480, "height", 520), mapped.get("defaultSize"));
        assertTrue(mapped.get("inputs") instanceof List<?>);
    }
}
