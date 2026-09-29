package com.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatStreamEventNormalizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesReasoningAndContentAcrossSplitSseFrames() throws Exception {
        List<JsonNode> events = new ArrayList<>();
        ChatStreamEventNormalizer normalizer = new ChatStreamEventNormalizer(
                line -> events.add(read(line)));

        normalizer.accept(bytes("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先分析"));
        normalizer.accept(bytes("需求\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"正式"));
        normalizer.accept(bytes("输出\"}}]}\n\ndata: [DONE]\n\n"));
        normalizer.complete();

        assertThat(events)
                .extracting(node -> node.path("type").asText())
                .containsExactly("reasoning_delta", "content_delta", "done");
        assertThat(events.get(0).path("delta").asText()).isEqualTo("先分析需求");
        assertThat(events.get(1).path("delta").asText()).isEqualTo("正式输出");
        assertThat(events)
                .extracting(node -> node.path("seq").asInt())
                .containsExactly(1, 2, 3);
    }

    @Test
    void convertsStandaloneGeminiStyleResponse() throws Exception {
        List<JsonNode> events = new ArrayList<>();
        ChatStreamEventNormalizer normalizer = new ChatStreamEventNormalizer(
                line -> events.add(read(line)));

        normalizer.accept(bytes("""
                {"choices":[{"message":{"content":"一次性输出"},"finish_reason":"stop"}]}
                """));
        normalizer.complete();

        assertThat(events)
                .extracting(node -> node.path("type").asText())
                .containsExactly("content_delta", "done");
        assertThat(events.get(0).path("delta").asText()).isEqualTo("一次性输出");
    }

    @Test
    void emitsErrorFrameForUpstreamError() throws Exception {
        List<JsonNode> events = new ArrayList<>();
        ChatStreamEventNormalizer normalizer = new ChatStreamEventNormalizer(
                line -> events.add(read(line)));

        normalizer.error("upstream failed");
        normalizer.complete();

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().path("type").asText()).isEqualTo("error");
        assertThat(events.getFirst().path("message").asText()).isEqualTo("upstream failed");
    }

    private JsonNode read(String line) {
        try {
            return objectMapper.readTree(line);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
