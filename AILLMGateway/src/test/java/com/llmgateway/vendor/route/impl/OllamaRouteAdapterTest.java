package com.llmgateway.vendor.route.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.entity.Channel;
import com.llmgateway.entity.ModelParams;
import com.llmgateway.mapper.ModelParamsMapper;
import com.llmgateway.service.OllamaFormatConverter;
import com.llmgateway.service.RouteResult;
import com.llmgateway.service.UpstreamClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OllamaRouteAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final UpstreamClient upstream = mock(UpstreamClient.class);
    private final ModelParamsMapper modelParamsMapper = mock(ModelParamsMapper.class);
    private final OllamaFormatConverter converter = new OllamaFormatConverter();
    private final OllamaRouteAdapter adapter = new OllamaRouteAdapter(upstream, converter, modelParamsMapper);

    private Channel channel() {
        Channel c = new Channel();
        c.setName("ollama");
        c.setType("ollama");
        c.setBaseUrl("http://localhost:11434/api");
        c.setApiKey("");
        return c;
    }

    private ModelParams modelParams() {
        ModelParams mp = new ModelParams();
        mp.setModelName("gemma4");
        mp.setType("text");
        mp.setTemperature("0.7");
        mp.setMaxTokens(1024);
        mp.setTopP("0.9");
        mp.setOllamaParams("{\"local\":true,\"think\":true,\"think_level\":\"high\",\"num_ctx\":8192}");
        return mp;
    }

    @Test
    void forward_appliesModelParamsDefaults() throws Exception {
        when(modelParamsMapper.selectOne(any())).thenReturn(modelParams());
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("{\"model\":\"gemma4\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                + "\"done\":true,\"prompt_eval_count\":1,\"eval_count\":1}");
        when(upstream.postJson(anyString(), anyString(), anyString(), anyString())).thenReturn(resp);

        String req = "{\"model\":\"gemma4\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false}";
        RouteResult result = adapter.forward("/chat/completions", req, channel(), "key");

        assertEquals(200, result.status());
        assertTrue(result.body().contains("\"content\":\"ok\""));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(upstream).postJson(eq("http://localhost:11434/api"), eq("/chat"), eq("key"), body.capture());
        JsonNode sent = mapper.readTree(body.getValue());
        assertEquals(8192, sent.path("options").path("num_ctx").asInt());
        assertEquals(1024, sent.path("options").path("num_predict").asInt());
        assertEquals(0.7, sent.path("options").path("temperature").asDouble(), 1e-9);
        assertEquals("high", sent.path("think").asText());
    }

    @Test
    void forward_clientValueOverridesDefaults() throws Exception {
        when(modelParamsMapper.selectOne(any())).thenReturn(modelParams());
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("{\"model\":\"gemma4\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"done\":true}");
        when(upstream.postJson(anyString(), anyString(), anyString(), anyString())).thenReturn(resp);

        String req = "{\"model\":\"gemma4\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false,"
                + "\"temperature\":0.2,\"max_tokens\":32}";
        adapter.forward("/chat/completions", req, channel(), "key");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(upstream).postJson(eq("http://localhost:11434/api"), eq("/chat"), eq("key"), body.capture());
        JsonNode sent = mapper.readTree(body.getValue());
        assertEquals(0.2, sent.path("options").path("temperature").asDouble(), 1e-9);
        assertEquals(32, sent.path("options").path("num_predict").asInt());
        assertEquals(8192, sent.path("options").path("num_ctx").asInt());
    }

    @Test
    void stream_convertsNdjsonToSse() throws Exception {
        when(modelParamsMapper.selectOne(any())).thenReturn(modelParams());
        String ndjson = "{\"message\":{\"content\":\"hel\"},\"done\":false,\"model\":\"gemma4\"}\n"
                + "{\"message\":{\"content\":\"o\"},\"done\":true,\"model\":\"gemma4\"}\n";
        HttpResponse<java.io.InputStream> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn(new ByteArrayInputStream(ndjson.getBytes(StandardCharsets.UTF_8)));
        when(upstream.postJsonStream(anyString(), anyString(), anyString(), anyString())).thenReturn(resp);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String req = "{\"model\":\"gemma4\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}";
        adapter.stream("/chat/completions", req, channel(), "key", out::writeBytes);

        String sse = out.toString(StandardCharsets.UTF_8);
        assertTrue(sse.contains("data: "));
        assertTrue(sse.contains("\"content\":\"hel\""));
        assertTrue(sse.contains("data: [DONE]"));
    }

    @Test
    void images_returnsUnsupportedError() throws Exception {
        RouteResult result = adapter.forward("/images/generations", "{}", channel(), "key");
        assertEquals(400, result.status());
        assertTrue(result.body().contains("不支持"));
    }

    @Test
    void httpImage_downloadsAndBase64Encodes() throws Exception {
        when(modelParamsMapper.selectOne(any())).thenReturn(modelParams());
        when(upstream.getBytes("http://x/a.png")).thenReturn(new byte[]{1, 2, 3});
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("{\"model\":\"llava\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"done\":true}");
        when(upstream.postJson(anyString(), anyString(), anyString(), anyString())).thenReturn(resp);

        String req = "{\"model\":\"llava\",\"messages\":[{\"role\":\"user\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"describe\"},"
                + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"http://x/a.png\"}}]}],\"stream\":false}";
        adapter.forward("/chat/completions", req, channel(), "key");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(upstream).postJson(eq("http://localhost:11434/api"), eq("/chat"), eq("key"), body.capture());
        JsonNode sent = mapper.readTree(body.getValue());
        String b64 = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        assertEquals(b64, sent.path("messages").get(0).get("images").get(0).asText());
    }

    @Test
    void forward_ignoresNullOllamaParams() throws Exception {
        ModelParams mp = new ModelParams();
        mp.setModelName("gemma4");
        mp.setTemperature("0.5");
        mp.setMaxTokens(64);
        mp.setOllamaParams("{\"local\":true,\"think\":false,\"think_level\":\"high\","
                + "\"num_ctx\":null,\"seed\":null,\"stop\":null,\"keep_alive\":null}");
        when(modelParamsMapper.selectOne(any())).thenReturn(mp);
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("{\"model\":\"gemma4\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"done\":true}");
        when(upstream.postJson(anyString(), anyString(), anyString(), anyString())).thenReturn(resp);

        String req = "{\"model\":\"gemma4\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false}";
        adapter.forward("/chat/completions", req, channel(), "key");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(upstream).postJson(eq("http://localhost:11434/api"), eq("/chat"), eq("key"), body.capture());
        JsonNode sent = mapper.readTree(body.getValue());
        assertFalse(sent.has("keep_alive"));
        assertFalse(sent.has("num_ctx"));
        assertFalse(sent.has("seed"));
        assertFalse(sent.has("stop"));
        assertFalse(sent.path("think").asBoolean());
        assertFalse(sent.has("think_level"));
    }

    @Test
    void forward_visionRequest_usesVisionParams() throws Exception {
        ModelParams vision = new ModelParams();
        vision.setModelName("gemma4");
        vision.setType("vision");
        vision.setOllamaParams("{\"local\":true,\"think\":true,\"think_level\":\"high\"}");
        when(modelParamsMapper.selectOne(any())).thenReturn(vision);
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("{\"model\":\"gemma4\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"done\":true}");
        when(upstream.postJson(anyString(), anyString(), anyString(), anyString())).thenReturn(resp);

        String req = "{\"model\":\"gemma4\",\"messages\":[{\"role\":\"user\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"describe\"},"
                + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,ABC\"}}]}],\"stream\":false}";
        adapter.forward("/chat/completions", req, channel(), "key");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(upstream).postJson(eq("http://localhost:11434/api"), eq("/chat"), eq("key"), body.capture());
        JsonNode sent = mapper.readTree(body.getValue());
        assertEquals("high", sent.path("think").asText());
    }

    @Test
    void forward_textRequest_usesTextParams() throws Exception {
        ModelParams text = new ModelParams();
        text.setModelName("gemma4");
        text.setType("text");
        text.setOllamaParams("{\"local\":true,\"think\":false,\"think_level\":\"low\"}");
        when(modelParamsMapper.selectOne(any())).thenReturn(text);
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("{\"model\":\"gemma4\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"done\":true}");
        when(upstream.postJson(anyString(), anyString(), anyString(), anyString())).thenReturn(resp);

        String req = "{\"model\":\"gemma4\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false}";
        adapter.forward("/chat/completions", req, channel(), "key");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(upstream).postJson(eq("http://localhost:11434/api"), eq("/chat"), eq("key"), body.capture());
        JsonNode sent = mapper.readTree(body.getValue());
        assertFalse(sent.path("think").asBoolean());
    }
}
