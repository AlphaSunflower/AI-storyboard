package com.moon.moonagent.ai.agent.handler;

import com.moon.moonagent.ai.AgentAiConfigProperties;
import com.moon.moonagent.ai.ScriptGenerationService;
import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.ai.agent.AgentSceneItem;
import com.moon.moonagent.ai.agent.AssetMatchingService;
import com.moon.moonagent.ai.agent.storyboard.StoryboardPortfolioService;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.Scene;
import com.moon.moonagent.service.StoryboardAutoImageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AisplitIntentHandlerAutoImageTest {

    @Mock
    private AgentOrchestratorSupport support;
    @Mock
    private ScriptGenerationService scriptGenerationService;
    @Mock
    private AgentGenerationService generationService;
    @Mock
    private StoryboardClient storyboardClient;
    @Mock
    private AgentAiConfigProperties agentConfig;
    @Mock
    private AssetMatchingService assetMatchingService;
    @Mock
    private StoryboardPortfolioService portfolioService;
    @Mock
    private StoryboardAutoImageService storyboardAutoImageService;

    @Test
    void resume_afterWritingStoryboard_enqueuesAutoImagesForPersistedSceneNumbers() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setProjectId("project-1");
        OrchestrationRequest request = new OrchestrationRequest(conversation, "", List.of(), null);
        request.setAction("agree");

        AgentCheckpoint checkpoint = new AgentCheckpoint();
        checkpoint.setAction("agree");
        checkpoint.setPlan("[]");

        AgentSceneItem planItem = new AgentSceneItem(
                7, "连续转场", "画面提示词", "视频提示词", "", "", "", "");
        Scene persisted = new Scene();
        persisted.setId("scene-1");
        persisted.setProjectId("project-1");
        persisted.setSceneNumber(1);

        when(support.referenceMaterialUrls("conversation-1")).thenReturn(List.of());
        when(support.parsePlanScenes("[]")).thenReturn(List.of(planItem));
        when(storyboardClient.getProjectScenes("project-1")).thenReturn(List.of(), List.of(persisted));
        when(generationService.writeScript("project-1", "conversation-1", List.of(planItem))).thenReturn(1);
        when(support.planRecommendedParams("[]")).thenReturn(Map.of());
        when(support.parsePlanAssetMatches("[]")).thenReturn(List.of());
        when(support.planListField("[]", "sceneBindings")).thenReturn(List.of());
        when(support.getSessionAssets("conversation-1")).thenReturn(List.of());
        AisplitIntentHandler handler = new AisplitIntentHandler(
                support, scriptGenerationService, generationService, storyboardClient,
                agentConfig, assetMatchingService, portfolioService, storyboardAutoImageService);

        String result = handler.resume(request, checkpoint);

        assertEquals("✅ 分镜方案已确认，已生成 **1 个分镜**。", result);
        verify(storyboardAutoImageService)
                .enqueueMissingMaterialImages("conversation-1", "project-1", List.of(1));
        verify(support).sendMessage(request, result);
        verify(support).sendEvent(
                request,
                "message_end",
                Map.of("messageId", "", "sceneCount", 1L, "content", result));
    }
}
