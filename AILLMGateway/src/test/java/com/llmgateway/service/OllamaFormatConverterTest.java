package com.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaFormatConverterTest {

    private final OllamaFormatConverter converter = new OllamaFormatConverter();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void textOnly_convertsToOllamaChat() throws Exception {
        String openAi = "{\"model\":\"gemma4\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                + "\"stream\":false,\"max_tokens\":512,\"temperature\":0.3,\"top_p\":0.9}";
        JsonNode out = mapper.readTree(converter.toOllamaChatRequest(openAi, Map.of(), url -> {
            throw new AssertionError("不应出现图片");
        }));

        assertEquals("gemma4", out.path("model").asText());
        assertEquals("hi", out.path("messages").get(0).path("content").asText());
        assertFalse(out.path("stream").asBoolean());
        assertEquals(512, out.path("options").path("num_predict").asInt());
        assertEquals(0.3, out.path("options").path("temperature").asDouble(), 1e-9);
        assertEquals(0.9, out.path("options").path("top_p").asDouble(), 1e-9);
    }

    @Test
    void multimodal_dataUrl_putsBase64IntoImages() throws Exception {
        String openAi = "{\"model\":\"llava\",\"messages\":[{\"role\":\"user\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"describe\"},"
                + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,ABC123\"}}]}],\"stream\":false}";
        JsonNode out = mapper.readTree(converter.toOllamaChatRequest(openAi, Map.of(), url -> "ABC123"));

        JsonNode msg = out.path("messages").get(0);
        assertEquals("describe", msg.path("content").asText());
        assertEquals("ABC123", msg.get("images").get(0).asText());
    }

    @Test
    void params_think_mergesDefaults() throws Exception {
        String openAi = "{\"model\":\"gemma4\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false}";
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("temperature", 0.7);
        defaults.put("max_tokens", 1024);
        defaults.put("think", true);
        defaults.put("think_level", "high");
        defaults.put("num_ctx", 8192);
        JsonNode out = mapper.readTree(converter.toOllamaChatRequest(openAi, defaults, url -> "x"));

        JsonNode options = out.path("options");
        assertEquals(0.7, options.path("temperature").asDouble(), 1e-9);
        assertEquals(1024, options.path("num_predict").asInt());
        assertEquals(8192, options.path("num_ctx").asInt());
        assertEquals("high", out.path("think").asText());
    }

    @Test
    void response_convertsToOpenAi() throws Exception {
        String ollamaRaw = "{\"model\":\"gemma4\",\"message\":{\"role\":\"assistant\",\"content\":\"hi\","
                + "\"thinking\":\"thinking...\",\"tool_calls\":[{\"function\":{\"name\":\"x\",\"arguments\":{\"a\":1}}}]},"
                + "\"done\":true,\"prompt_eval_count\":10,\"eval_count\":5}";
        JsonNode out = mapper.readTree(converter.toOpenAiChatResponse(ollamaRaw, "gemma4"));

        JsonNode choice = out.path("choices").get(0);
        assertEquals("hi", choice.path("message").path("content").asText());
        assertEquals("thinking...", choice.path("message").path("reasoning_content").asText());
        assertEquals("x", choice.path("message").path("tool_calls").get(0).path("function").path("name").asText());
        assertEquals("stop", choice.path("finish_reason").asText());
        assertEquals(10, out.path("usage").path("prompt_tokens").asInt());
        assertEquals(5, out.path("usage").path("completion_tokens").asInt());
        assertEquals(15, out.path("usage").path("total_tokens").asInt());
    }

    @Test
    void chunk_convertsNdjsonLineToSseJson() throws Exception {
        JsonNode line = mapper.readTree("{\"message\":{\"content\":\"hel\",\"thinking\":\"th\",\"role\":\"assistant\"},\"done\":false,\"model\":\"gemma4\"}");
        JsonNode chunk = mapper.readTree(converter.toChunkJson(line, "id1", "gemma4"));

        assertEquals("chat.completion.chunk", chunk.path("object").asText());
        assertEquals("id1", chunk.path("id").asText());
        JsonNode delta = chunk.path("choices").get(0).path("delta");
        assertEquals("hel", delta.path("content").asText());
        assertEquals("th", delta.path("reasoning_content").asText());
        assertTrue(chunk.path("choices").get(0).path("finish_reason").isNull());
    }
}
