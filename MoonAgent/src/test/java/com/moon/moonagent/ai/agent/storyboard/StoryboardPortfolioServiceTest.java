package com.moon.moonagent.ai.agent.storyboard;

import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.agent.AgentSceneItem;
import com.moon.moonagent.config.PromptConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class StoryboardPortfolioServiceTest {

    private final StoryboardPortfolioService service = new StoryboardPortfolioService(
            mock(GatewayModelService.class), mock(PromptConfig.class), mock(ChatClient.Builder.class));

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
}
