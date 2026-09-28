package com.moon.moonagent.ai.agent.handler;

import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentMessage;
import com.moon.moonagent.ai.AgentAiConfigProperties;
import com.moon.moonagent.ai.agent.AgentSceneItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class AgentRuntimeSupportMaterialContextTest {

    @Test
    void enrichMaterialContext_addsPicUrlsAndAssetIdsToEveryCard() {
        OrchestrationRequest request = new OrchestrationRequest(
                new AgentConversation(), "转场需求", List.of("img1.png", "img2.png"), new SseEmitter());
        request.setAssetIds(List.of("asset-1", "asset-2"));

        var enriched = AgentRuntimeSupport.enrichMaterialContext(
                request, List.of(Map.of("content", "转场需求"), Map.of("content", "second")));

        assertEquals(2, enriched.size());
        assertEquals(List.of("img1.png", "img2.png"), enriched.get(0).get("picUrls"));
        assertEquals(List.of("asset-1", "asset-2"), enriched.get(0).get("assetIds"));
        assertEquals(List.of("img1.png", "img2.png"), enriched.get(1).get("picUrls"));
        assertEquals(List.of("asset-1", "asset-2"), enriched.get(1).get("assetIds"));
    }

    @Test
    void enrichMaterialContext_preservesExistingFieldsWithoutOverwriting() {
        OrchestrationRequest request = new OrchestrationRequest(
                new AgentConversation(), "x", List.of("img"), new SseEmitter());

        var enriched = AgentRuntimeSupport.enrichMaterialContext(
                request, List.of(Map.of("content", "x", "picUrls", List.of("kept"))));

        assertEquals(List.of("kept"), enriched.get(0).get("picUrls"));
        assertFalse(enriched.get(0).containsKey("assetIds"));
    }

    @Test
    void enrichMaterialContext_returnsOriginalWhenNoMaterials() {
        OrchestrationRequest request = new OrchestrationRequest(
                new AgentConversation(), "x", List.of(), new SseEmitter());
        List<Map<String, Object>> payload = List.of(Map.of("content", "x"));

        var enriched = AgentRuntimeSupport.enrichMaterialContext(request, payload);

        assertTrue(enriched == payload);
    }

    @Test
    void planAssetIds_readsCheckpointCommaString() {
        String plan = "{\"items\":[{\"assetIds\":\"asset-1,asset-2\",\"picUrls\":[]}],\"_formContent\":\"x\"}";
        assertEquals(List.of("asset-1", "asset-2"), AgentRuntimeSupport.planAssetIds(plan));
    }

    @Test
    void planAssetIds_readsCheckpointJsonArray() {
        String plan = "{\"items\":[{\"assetIds\":[\"asset-a\",\"asset-b\"]}]}";
        assertEquals(List.of("asset-a", "asset-b"), AgentRuntimeSupport.planAssetIds(plan));
    }

    @Test
    void renderHistoryContext_truncatesEachMessageAndKeepsNewestWithinTotalBudget() {
        AgentMessage oldest = message("user", "old-message-that-does-not-fit");
        AgentMessage middle = message("assistant", "1234567890");
        AgentMessage newest = message("user", "newest-message");

        String rendered = AgentRuntimeSupport.renderHistoryContext(
                List.of(newest, middle, oldest), 6, 50);

        assertTrue(rendered.length() <= 50);
        assertTrue(rendered.contains("用户：newes…"));
        assertTrue(rendered.contains("助手：12345…"));
        assertFalse(rendered.contains("old-message"));
    }

    @Test
    void renderHistoryContext_keepsChronologicalOrder() {
        String rendered = AgentRuntimeSupport.renderHistoryContext(
                List.of(message("assistant", "new"), message("user", "old")), 0, 0);

        assertTrue(rendered.indexOf("用户：old") < rendered.indexOf("助手：new"));
    }

    @Test
    void requestOptions_carriesModelAndFiveMinuteTimeout() {
        OpenAiChatOptions options = AgentRuntimeSupport.requestOptions("gateway-text", 300).build();

        assertThat(options.getModel()).isEqualTo("gateway-text");
        assertThat(options.getTimeout()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void callPlainText_usesChatResponseReasoningAndPublishesDelta() throws Exception {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        ChatResponse response = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        AssistantMessage output = mock(AssistantMessage.class);
        com.moon.moonagent.ai.GatewayModelService gateway = mock(com.moon.moonagent.ai.GatewayModelService.class);
        SseEmitter emitter = mock(SseEmitter.class);

        when(builder.defaultOptions(any())).thenReturn(builder);
        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(response);
        when(response.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(output);
        when(output.getText()).thenReturn("优化后的剧本");
        when(output.getMetadata()).thenReturn(Map.of("reasoning_content", "先分析用户需求，再整理剧本结构。"));
        when(gateway.getDefaultTextModel()).thenReturn("test-model");

        AgentRuntimeSupport support = new AgentRuntimeSupport(
                null, builder, new AgentAiConfigProperties(), null, null, gateway, null,
                null, null, null, null, null);
        OrchestrationRequest request = new OrchestrationRequest(
                new AgentConversation(), "写一个转场分镜", List.of(), emitter);

        String content = support.callPlainText("system", "user", request);

        assertEquals("优化后的剧本", content);
        assertThat(request.getReasoning()).isNotNull();
        assertThat(request.getReasoning().details()).contains("先分析用户需求");
        assertThat(request.getReasoning().source()).isEqualTo("test-model");
        verify(emitter, atLeastOnce()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void parsePlanScenes_readsNumericAndStringDuration() {
        AgentRuntimeSupport support = new AgentRuntimeSupport(
                null, null, null, null, null, null, null,
                null, null, null, null, null);
        String plan = """
                {"items":[
                  {"sceneNumber":1,"scriptContent":"第一镜","duration":10},
                  {"sceneNumber":2,"scriptContent":"第二镜","duration":"12秒"}
                ]}
                """;

        List<AgentSceneItem> scenes = support.parsePlanScenes(plan);

        assertThat(scenes).hasSize(2);
        assertThat(scenes.get(0).duration()).isEqualTo(10);
        assertThat(scenes.get(1).duration()).isEqualTo(12);
    }

    @Test
    void messageEndEventIncludesTurnMetrics() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        AgentRuntimeSupport support = new AgentRuntimeSupport(
                null, null, null, null, null, null, null,
                null, null, null, null, null);
        OrchestrationRequest request = new OrchestrationRequest(
                new AgentConversation(), "hello", List.of(), emitter);
        AgentTraceAccumulator trace = new AgentTraceAccumulator(request);
        request.setTrace(trace);
        trace.beginModelCall();
        trace.recordUsage(50, 25, 10);

        support.sendEvent(request, "message_end", Map.of("content", "done"));

        var captor = org.mockito.ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(captor.capture());
        String json = captor.getValue().build().stream()
                .map(item -> String.valueOf(item.getData()))
                .filter(value -> value.startsWith("{"))
                .findFirst()
                .orElseThrow();
        var metrics = new ObjectMapper().readTree(json).path("metrics");
        assertThat(metrics.path("totalTokens").asLong()).isEqualTo(75L);
        assertThat(metrics.path("complete").asBoolean()).isTrue();
        assertThat(metrics.path("durationMs").asLong()).isGreaterThanOrEqualTo(0L);
    }

    private static AgentMessage message(String role, String content) {
        AgentMessage message = new AgentMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
