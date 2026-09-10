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
    void infersGroupCountFromPromptWhenExplicitCountIsOne() {
        JsonNode body = SeedreamImageProtocol.buildRequest(
                "doubao-seedream-4-0-250828",
                "生成四张同一角色的不同表情",
                List.of(),
                null,
                1);

        assertEquals("auto", body.path("sequential_image_generation").asText());
        assertEquals(4, body.path("sequential_image_generation_options").path("max_images").asInt());
        assertFalse(body.has("image"));
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
}
