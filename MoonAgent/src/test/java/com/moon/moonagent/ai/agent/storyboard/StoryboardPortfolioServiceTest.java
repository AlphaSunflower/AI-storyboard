package com.moon.moonagent.ai.agent.storyboard;

import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.AgentAiConfigProperties;
import com.moon.moonagent.ai.agent.AgentReasoningSnapshot;
import com.moon.moonagent.ai.agent.AgentSceneItem;
import com.moon.moonagent.config.PromptConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoryboardPortfolioServiceTest {

    private final StoryboardPortfolioService service = new StoryboardPortfolioService(
            mock(GatewayModelService.class), mock(PromptConfig.class), mock(ChatClient.Builder.class),
            new AgentAiConfigProperties());

    @Test
    void role_parse_and_default() {
        assertEquals(StoryboardReferenceRole.FIRST_FRAME, StoryboardReferenceRole.parse("first_frame"));
        assertEquals(StoryboardReferenceRole.LAST_FRAME, StoryboardReferenceRole.parse(" 尾帧 "));
        assertEquals(StoryboardReferenceRole.FIRST_LAST_FRAME, StoryboardReferenceRole.parse("FIRST_LAST_FRAME"));
        assertEquals(StoryboardReferenceRole.FULL_REFERENCE, StoryboardReferenceRole.parse("全能参考"));
        assertEquals(null, StoryboardReferenceRole.parse("bogus"));
        assertEquals(StoryboardReferenceRole.FIRST_FRAME, StoryboardReferenceRole.defaultRole(1));
        assertEquals(StoryboardReferenceRole.FIRST_LAST_FRAME, StoryboardReferenceRole.defaultRole(2));
        assertEquals(StoryboardReferenceRole.FULL_REFERENCE, StoryboardReferenceRole.defaultRole(4));
    }

    @Test
    void buildH3VideoPrompt_emitsReferenceSectionAndNA() {
        StoryboardReferenceBinding b1 = new StoryboardReferenceBinding("图1", "", "reference",
                "人物参考（锁脸）", "她持剑而立", null, "FIRST_FRAME");
        StoryboardReferenceBinding b2 = new StoryboardReferenceBinding("图2", "", "reference",
                "场景参考", "樱花庭院", null, "FULL_REFERENCE");
        String prompt = StoryboardPortfolioService.buildH3VideoPrompt("一位女剑客在樱花庭院舞剑", List.of(b1, b2));

        assertTrue(prompt.contains("【参考素材说明】"));
        assertTrue(prompt.contains("@图片1"));
        assertTrue(prompt.contains("人物参考（锁脸）"));
        assertTrue(prompt.contains("作为首帧"));
        assertTrue(prompt.contains("作为全能参考"));
        assertTrue(prompt.contains("【核心创意】"));
        assertTrue(prompt.contains("【画面过程说明】"));
        assertTrue(prompt.contains("非叙事性音乐：N/A"));
    }

    @Test
    void designPlan_llmFailure_returnsNullInsteadOfFakeSingleScene() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        GatewayModelService gateway = mock(GatewayModelService.class);
        PromptConfig promptConfig = mock(PromptConfig.class);

        when(builder.defaultOptions(any())).thenReturn(builder);
        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenThrow(new RuntimeException("Request failed"));
        when(gateway.getDefaultTextModel()).thenReturn("test-model");
        when(promptConfig.get("orchestrator/storyboard-plan-designer")).thenReturn("test-system");

        StoryboardPortfolioService failedService = new StoryboardPortfolioService(
                gateway, promptConfig, builder, new AgentAiConfigProperties());
        StoryboardReferenceBinding binding = new StoryboardReferenceBinding(
                "图1", "", "reference", "人物参考", "她舞剑", null, "FIRST_FRAME");

        StoryboardDesignPlan plan = failedService.designPlan("第一镜她拔剑，第二镜她跃起", List.of(binding));

        assertNull(plan, "方案设计失败时不能伪造单个超长分镜");
        verify(callSpec).chatResponse();
    }

    @Test
    void designPlan_llmReturnsBlankUrl_restoresSourceBindingUrl() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        GatewayModelService gateway = mock(GatewayModelService.class);
        PromptConfig promptConfig = mock(PromptConfig.class);

        when(builder.defaultOptions(any())).thenReturn(builder);
        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        mockChatResponse(callSpec, """
                {"scenes":[{"sceneNumber":1,"scriptContent":"第一空间转第二空间",
                "imagePrompt":"转场画面","videoPrompt":"【参考素材说明】@图片1",
                "negativePrompt":"","cameraMovement":"缓慢推进","shotType":"全景","soundDesign":"环境音",
                "referenceBindings":[{"assetId":"图1","url":"","kind":"reference",
                "purposeText":"第一空间","supportedPrompt":"转场","groupLabel":"第一空间","role":"FIRST_FRAME"}]}],
                "aspectRatio":"16:9","message":"ok"}
                """);
        when(gateway.getDefaultTextModel()).thenReturn("test-model");
        when(promptConfig.get("orchestrator/storyboard-plan-designer")).thenReturn("test-system");

        StoryboardPortfolioService configuredService = new StoryboardPortfolioService(
                gateway, promptConfig, builder, new AgentAiConfigProperties());
        StoryboardReferenceBinding source = new StoryboardReferenceBinding(
                "图1", "/api/files/images/first.png", "reference",
                "第一空间", "转场", "第一空间", "FIRST_FRAME");

        StoryboardDesignPlan plan = configuredService.designPlan("第一空间转第二空间", List.of(source));

        assertEquals("/api/files/images/first.png",
                plan.scenes().getFirst().referenceBindings().getFirst().url());
    }

    @Test
    void designPlan_publishesModelReasoningToListener() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        GatewayModelService gateway = mock(GatewayModelService.class);
        PromptConfig promptConfig = mock(PromptConfig.class);

        when(builder.defaultOptions(any())).thenReturn(builder);
        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        mockChatResponse(callSpec, """
                {"scenes":[{"sceneNumber":1,"scriptContent":"空间转场",
                "imagePrompt":"转场画面","videoPrompt":"【参考素材说明】@图片1",
                "negativePrompt":"","cameraMovement":"推进","shotType":"全景","soundDesign":"环境音",
                "referenceBindings":[]}],"aspectRatio":"16:9","message":"ok"}
                """, Map.of("reasoning_content", "先匹配两张空间的同形边界，再安排连续运镜。"));
        when(gateway.getDefaultTextModel()).thenReturn("test-model");
        when(promptConfig.get("orchestrator/storyboard-plan-designer")).thenReturn("test-system");

        StoryboardPortfolioService configuredService = new StoryboardPortfolioService(
                gateway, promptConfig, builder, new AgentAiConfigProperties());
        List<AgentReasoningSnapshot> seen = new ArrayList<>();

        configuredService.designPlan("第一空间转第二空间", List.of(new StoryboardReferenceBinding(
                "图1", "/api/files/images/first.png", "reference",
                "第一空间", "转场", "第一空间", "FIRST_FRAME")), seen::add);

        assertEquals(1, seen.size());
        assertTrue(seen.get(0).details().contains("先匹配两张空间"));
        assertEquals("test-model", seen.get(0).source());
    }

    @Test
    void toScenes_producesAgentSceneItems_withH3Fallback() {
        StoryboardReferenceBinding b = new StoryboardReferenceBinding("图1", "", "reference",
                "人物参考", "她舞剑", null, "FIRST_FRAME");
        StoryboardScenePlan s = new StoryboardScenePlan(1, "女剑客舞剑", "图1构图", "", "", "", "全景", "", List.of(b));
        StoryboardDesignPlan plan = new StoryboardDesignPlan(List.of(s), "16:9", "");

        List<AgentSceneItem> items = service.toScenes(plan);
        assertEquals(1, items.size());
        AgentSceneItem item = items.get(0);
        assertEquals("女剑客舞剑", item.scriptContent());
        assertTrue(item.videoPrompt().contains("【参考素材说明】"));
        assertTrue(item.videoPrompt().contains("@图片1"));
        assertTrue(item.videoPrompt().contains("非叙事性音乐：N/A"));
        assertEquals(1, item.sceneNumber());
    }

    @Test
    void toScenes_handlesBlankVideoPrompt() {
        StoryboardScenePlan s = new StoryboardScenePlan(2, "无图场景", "", "", "", "", "", "", List.of());
        StoryboardDesignPlan plan = new StoryboardDesignPlan(new ArrayList<>(List.of(s)), "9:16", "");
        List<AgentSceneItem> items = service.toScenes(plan);
        assertEquals(1, items.size());
        assertTrue(items.get(0).videoPrompt().contains("【画面过程说明】"));
    }

    @Test
    void bindMaterials_assetUploadWithPurpose_isKeptAsAssetWithoutLlm() {
        List<StoryboardReferenceBinding> out = service.bindMaterials(
                "一位女剑客在樱花庭院舞剑",
                List.of("/api/files/images/a.png"),
                List.of(),
                Map.of("/api/files/images/a.png", "人物参考（锁脸）"),
                Map.of("/api/files/images/a.png", "asset"));

        assertEquals(1, out.size());
        StoryboardReferenceBinding b = out.get(0);
        assertEquals("asset", b.kind());
        assertEquals("人物参考（锁脸）", b.purposeText());
        assertEquals("图1", b.assetId());
    }

    @Test
    void bindMaterials_referenceWithoutPurpose_fallsBackToUnspecified() {
        List<StoryboardReferenceBinding> out = service.bindMaterials(
                "写实电影",
                List.of("/api/files/images/b.png", "/api/files/images/c.png"),
                List.of(),
                Map.of(),
                Map.of());

        assertEquals(2, out.size());
        assertEquals("reference", out.get(0).kind());
        assertEquals("用户未明确说明", out.get(0).purposeText());
        assertEquals("图1", out.get(0).assetId());
        assertEquals("图2", out.get(1).assetId());
    }

    @Test
    void bindMaterials_referenceLlmReturnsBlankUrl_restoresUploadedUrl() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        GatewayModelService gateway = mock(GatewayModelService.class);
        PromptConfig promptConfig = mock(PromptConfig.class);

        when(builder.defaultOptions(any())).thenReturn(builder);
        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        mockChatResponse(callSpec, """
                {"bindings":[
                {"assetId":"图片1","url":"","kind":"reference","purposeText":"第一空间",
                "supportedPrompt":"转场起点","groupLabel":"第一空间","role":""},
                {"assetId":"图片2","url":"","kind":"reference","purposeText":"第二空间",
                "supportedPrompt":"转场终点","groupLabel":"第二空间","role":""}],
                "message":"ok"}
                """);
        when(gateway.getDefaultTextModel()).thenReturn("test-model");
        when(promptConfig.get("orchestrator/material-bind")).thenReturn("test-system");

        StoryboardPortfolioService configuredService = new StoryboardPortfolioService(
                gateway, promptConfig, builder, new AgentAiConfigProperties());
        List<StoryboardReferenceBinding> out = configuredService.bindMaterials(
                "第一空间转第二空间",
                List.of("/api/files/images/first.png", "/api/files/images/second.png"),
                List.of(), Map.of(), Map.of());

        assertEquals("/api/files/images/first.png", out.get(0).url());
        assertEquals("/api/files/images/second.png", out.get(1).url());
    }

    private static void mockChatResponse(ChatClient.CallResponseSpec callSpec, String raw) {
        mockChatResponse(callSpec, raw, Map.of());
    }

    private static void mockChatResponse(ChatClient.CallResponseSpec callSpec, String raw,
                                         Map<String, Object> metadata) {
        ChatResponse response = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        AssistantMessage output = mock(AssistantMessage.class);
        when(callSpec.chatResponse()).thenReturn(response);
        when(response.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(output);
        when(output.getText()).thenReturn(raw);
        when(output.getMetadata()).thenReturn(metadata);
    }
}
