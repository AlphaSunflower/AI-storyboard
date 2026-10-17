package com.storyboard.common.image;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedreamImageProtocolTest {

    @Test
    void identifiesSeedream4Models() {
        assertTrue(SeedreamImageProtocol.isSeedream("doubao-seedream-4-0-250828"));
        assertTrue(SeedreamImageProtocol.isSeedream("doubao-seedream-4-5-251128"));
        assertFalse(SeedreamImageProtocol.isSeedream("gpt-image-2"));
        assertFalse(SeedreamImageProtocol.isSeedream(null));
    }

    @Test
    void normalizesResolutionTierAndFallsBackTo2k() {
        assertEquals("1K", SeedreamImageProtocol.normalizeSize("1K"));
        assertEquals("2K", SeedreamImageProtocol.normalizeSize("2k"));
        assertEquals("4K", SeedreamImageProtocol.normalizeSize("4K"));
        assertEquals("2K", SeedreamImageProtocol.normalizeSize("2048x1152"));
        assertEquals("2K", SeedreamImageProtocol.normalizeSize(null));
    }

    @Test
    void buildsSequentialRequestWithInputOrderAndExplicitMaxImages() {
        JsonNode body = SeedreamImageProtocol.buildRequest(
                "doubao-seedream-4-0-250828",
                "融合图一和图二，生成三张统一风格的海报",
                List.of("data:image/png;base64,AAA", "https://example.com/two.png"),
                "1K",
                3);

        assertEquals("doubao-seedream-4-0-250828", body.path("model").asText());
        assertEquals("1K", body.path("size").asText());
        assertEquals(2, body.path("image").size());
        assertEquals("data:image/png;base64,AAA", body.path("image").get(0).asText());
        assertEquals("auto", body.path("sequential_image_generation").asText());
        assertEquals(3, body.path("sequential_image_generation_options").path("max_images").asInt());
        assertTrue(body.path("stream").asBoolean());
        assertEquals("url", body.path("response_format").asText());
        assertFalse(body.has("output_format"));
        assertFalse(body.path("watermark").asBoolean());
    }

    @Test
    void explicitCountOneWinsOverPromptCount() {
        JsonNode body = SeedreamImageProtocol.buildRequest(
                "doubao-seedream-4-0-250828",
                "生成四张同一角色的不同表情",
                List.of(),
                null,
                1);

        assertEquals("disabled", body.path("sequential_image_generation").asText());
        assertFalse(body.has("sequential_image_generation_options"));
        assertFalse(body.has("image"));
    }

    @Test
    void autoModeLetsTheModelChooseTheImageCount() {
        JsonNode body = SeedreamImageProtocol.buildRequest(
                "doubao-seedream-4-0-250828",
                "根据参考图设计一组空间效果图",
                List.of("https://example.com/a.png"),
                "1K",
                0);

        assertEquals("auto", body.path("sequential_image_generation").asText());
        assertFalse(body.has("sequential_image_generation_options"));
        assertEquals("根据参考图设计一组空间效果图", body.path("prompt").asText());
    }

    @Test
    void explicitCountWinsOverPromptCount() {
        JsonNode body = SeedreamImageProtocol.buildRequest(
                "doubao-seedream-4-0-250828",
                "生成四张同一角色的不同表情",
                List.of(),
                "1K",
                2);

        assertEquals(2, body.path("sequential_image_generation_options").path("max_images").asInt());
        assertTrue(body.path("prompt").asText().contains("构图、视角或场景细节上必须有明显差异"));
        assertTrue(body.path("prompt").asText().contains("不要输出重复或近似图片"));
    }

    @Test
    void addsExplicitGroupInstructionWhenCountIsGreaterThanOne() {
        JsonNode body = SeedreamImageProtocol.buildRequest(
                "doubao-seedream-4-0-250828",
                "一只橘猫坐在窗边看雨",
                List.of(),
                "1K",
                2);

        assertTrue(body.path("prompt").asText().contains("构图、视角或场景细节上必须有明显差异"));
        assertEquals(2, body.path("sequential_image_generation_options").path("max_images").asInt());
    }

    @Test
    void disablesSequentialGenerationWhenRequestedCountIsOne() {
        JsonNode body = SeedreamImageProtocol.buildRequest(
                "doubao-seedream-4-0-250828",
                "将这些图合为一个四宫格",
                List.of("https://example.com/a.png", "https://example.com/b.png"),
                "1K",
                1);

        assertEquals("disabled", body.path("sequential_image_generation").asText());
        assertFalse(body.has("sequential_image_generation_options"));
        assertEquals(2, body.path("image").size());
    }

    @Test
    void rejectsTooManyInputImagesOrTotalImages() {
        assertThrows(IllegalArgumentException.class, () ->
                SeedreamImageProtocol.validateLimits(15, 1));
        assertThrows(IllegalArgumentException.class, () ->
                SeedreamImageProtocol.validateLimits(2, 14));
    }

    @Test
    void parsesPartialSucceededAndCompletedEvents() {
        SeedreamImageProtocol.Event success = SeedreamImageProtocol.parseEvent("""
                event: image_generation.partial_succeeded
                data: {"type":"image_generation.partial_succeeded","model":"doubao-seedream-4-0-250828",
                data: "image_index":1,"url":"https://example.com/1.jpeg","size":"2048x2048"}
                """);
        SeedreamImageProtocol.Event completed = SeedreamImageProtocol.parseEvent("""
                event: image_generation.completed
                data: {"type":"image_generation.completed","model":"doubao-seedream-4-0-250828",
                data: "usage":{"generated_images":1,"total_tokens":42}}
                """);

        assertEquals(SeedreamImageProtocol.EventType.PARTIAL_SUCCEEDED, success.type());
        assertEquals(1, success.imageIndex());
        assertEquals("https://example.com/1.jpeg", success.url());
        assertEquals(SeedreamImageProtocol.EventType.COMPLETED, completed.type());
        assertEquals(1, completed.usage().path("generated_images").asInt());
    }

    @Test
    void parsesPartialFailedAndTopLevelErrorEvents() {
        SeedreamImageProtocol.Event failed = SeedreamImageProtocol.parseEvent("""
                data: {"type":"image_generation.partial_failed","image_index":2,
                data: "error":{"code":"Sensitive","message":"blocked"}}
                """);
        SeedreamImageProtocol.Event error = SeedreamImageProtocol.parseEvent("""
                data: {"error":{"code":"BadRequest","message":"missing prompt"}}
                """);

        assertEquals(SeedreamImageProtocol.EventType.PARTIAL_FAILED, failed.type());
        assertEquals(2, failed.imageIndex());
        assertEquals("blocked", failed.message());
        assertEquals(SeedreamImageProtocol.EventType.ERROR, error.type());
        assertEquals("missing prompt", error.message());
    }

    @Test
    void readsMultipleEventsFromSseStream() throws Exception {
        String sse = """
                event: image_generation.partial_succeeded
                data: {"type":"image_generation.partial_succeeded","image_index":0,
                data: "url":"https://example.com/0.jpeg"}

                event: image_generation.completed
                data: {"type":"image_generation.completed","usage":{"generated_images":1}}

                """;
        List<SeedreamImageProtocol.Event> events = new ArrayList<>();

        SeedreamImageProtocol.readEvents(
                new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)), events::add);

        assertEquals(2, events.size());
        assertEquals(SeedreamImageProtocol.EventType.PARTIAL_SUCCEEDED, events.get(0).type());
        assertEquals(SeedreamImageProtocol.EventType.COMPLETED, events.get(1).type());
    }

    // ── 按模型能力裁剪参数（方舟对 5.0 pro/flash 拒绝组图与流式参数） ──────────────

    @Test
    void recognizesCapabilitiesByModel() {
        SeedreamImageProtocol.Capabilities flash = SeedreamImageProtocol.capabilities(
                "doubao-seedream-5-0-flash-260915");
        assertFalse(flash.groupImages());
        assertFalse(flash.streaming());
        assertEquals(List.of("1K", "1.5K", "2K"), flash.sizeTiers());
        assertEquals(10, flash.maxInputImages());

        SeedreamImageProtocol.Capabilities pro = SeedreamImageProtocol.capabilities(
                "doubao-seedream-5-0-pro-260628");
        assertFalse(pro.groupImages());
        assertFalse(pro.streaming());

        // lite 及基础模型别名（doubao-seedream-5-0-<版本号>）支持组图与流式
        SeedreamImageProtocol.Capabilities lite = SeedreamImageProtocol.capabilities(
                "doubao-seedream-5-0-260128");
        assertTrue(lite.groupImages());
        assertTrue(lite.streaming());
        assertEquals(List.of("2K", "3K", "4K"), lite.sizeTiers());
        assertTrue(SeedreamImageProtocol.supportsGroupImages("doubao-seedream-5-0-lite-260128"));

        SeedreamImageProtocol.Capabilities v45 = SeedreamImageProtocol.capabilities(
                "doubao-seedream-4-5-251128");
        assertTrue(v45.groupImages());
        assertTrue(v45.streaming());
        assertEquals(List.of("2K", "4K"), v45.sizeTiers());

        SeedreamImageProtocol.Capabilities v40 = SeedreamImageProtocol.capabilities(
                "doubao-seedream-4-0-250828");
        assertEquals(List.of("1K", "2K", "4K"), v40.sizeTiers());
        assertEquals(14, v40.maxInputImages());
    }

    @Test
    void omitsUnsupportedParametersForSingleImageModels() {
        JsonNode body = SeedreamImageProtocol.buildRequest(
                "doubao-seedream-5-0-flash-260915",
                "一只橘猫坐在窗边看雨",
                List.of("https://example.com/a.png", "https://example.com/b.png"),
                "4K",
                4);

        assertFalse(body.has("sequential_image_generation"));
        assertFalse(body.has("sequential_image_generation_options"));
        assertFalse(body.has("stream"));
        assertEquals("2K", body.path("size").asText());
        assertEquals(2, body.path("image").size());
        assertEquals("一只橘猫坐在窗边看雨", body.path("prompt").asText());
    }

    @Test
    void keepsGroupAndStreamParametersForGroupImageModels() {
        JsonNode body = SeedreamImageProtocol.buildRequest(
                "doubao-seedream-4-0-250828", "一只橘猫", List.of(), "1K", 1);

        assertEquals("disabled", body.path("sequential_image_generation").asText());
        assertTrue(body.path("stream").asBoolean());
    }

    @Test
    void degradesSizesOutsideTheModelTierSet() {
        assertEquals("2K", SeedreamImageProtocol.normalizeSize("doubao-seedream-5-0-flash-260915", "4K"));
        assertEquals("1.5K", SeedreamImageProtocol.normalizeSize("doubao-seedream-5-0-flash-260915", "1.5K"));
        assertEquals("2K", SeedreamImageProtocol.normalizeSize("doubao-seedream-4-5-251128", "1K"));
        assertEquals("2K", SeedreamImageProtocol.normalizeSize("doubao-seedream-5-0-260128", "1K"));
        assertEquals("4K", SeedreamImageProtocol.normalizeSize("doubao-seedream-4-0-250828", "4K"));
    }

    @Test
    void singleImageModelIgnoresRequestedCountAndHasLowerReferenceLimit() {
        assertEquals(1, SeedreamImageProtocol.resolveRequestedCount(
                "doubao-seedream-5-0-flash-260915", "生成四张同一角色的不同表情", 4));
        assertThrows(IllegalArgumentException.class, () ->
                SeedreamImageProtocol.validateLimits("doubao-seedream-5-0-flash-260915", 11, 1));
        // 单图模型忽略输出张数，但 4.0 仍校验“输入 + 输出 ≤ 15”
        SeedreamImageProtocol.validateLimits("doubao-seedream-5-0-flash-260915", 10, 4);
        assertThrows(IllegalArgumentException.class, () ->
                SeedreamImageProtocol.validateLimits("doubao-seedream-4-0-250828", 2, 14));
    }

    @Test
    void parsesNonStreamingJsonResponse() {
        List<SeedreamImageProtocol.Event> events = SeedreamImageProtocol.parseSyncResponse("""
                {"created":1,"model":"doubao-seedream-5-0-flash-260915",
                 "data":[{"url":"https://cdn/0.jpeg","size":"2048x2048"},
                         {"error":{"code":"Sensitive","message":"blocked"}}],
                 "usage":{"generated_images":1}}
                """);

        assertEquals(3, events.size());
        assertEquals(SeedreamImageProtocol.EventType.PARTIAL_SUCCEEDED, events.get(0).type());
        assertEquals("https://cdn/0.jpeg", events.get(0).url());
        assertEquals(0, events.get(0).imageIndex());
        assertEquals(SeedreamImageProtocol.EventType.PARTIAL_FAILED, events.get(1).type());
        assertEquals("blocked", events.get(1).message());
        assertEquals(SeedreamImageProtocol.EventType.COMPLETED, events.get(2).type());
        assertEquals(1, events.get(2).usage().path("generated_images").asInt());
    }

    @Test
    void parsesNonStreamingTopLevelError() {
        List<SeedreamImageProtocol.Event> events = SeedreamImageProtocol.parseSyncResponse(
                """
                {"error":{"code":"InvalidParameter","message":"bad size"}}
                """);

        assertEquals(1, events.size());
        assertEquals(SeedreamImageProtocol.EventType.ERROR, events.get(0).type());
        assertEquals("InvalidParameter", events.get(0).code());
        assertEquals("bad size", events.get(0).message());
    }
}
