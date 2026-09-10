package com.moon.moonagent.ai.agent.handler;

import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.Scene;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SceneReviewCardServiceTest {

    @Test
    void openReview_buildsExpandedPayloadWithEverySceneImage() {
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        SceneReviewCardService service = new SceneReviewCardService(support);
        when(support.buildModels("image")).thenReturn(List.of());
        when(support.buildModels("video")).thenReturn(List.of());
        when(support.runHITLStage(any(), any(), any())).thenReturn("请选择要执行的操作：");

        Scene first = scene("s1", 1, "/api/files/images/a.png,/api/files/images/b.png");
        Scene second = scene("s2", 2, null);
        second.setImageUrl("/api/files/images/c.png");

        OrchestrationRequest request = request();
        String result = service.openReview(request, List.of(first, second));

        assertThat(result).isEqualTo("请选择要执行的操作：");
        ArgumentCaptor<AgentOrchestratorSupport.StagePlan> planCaptor =
                ArgumentCaptor.forClass(AgentOrchestratorSupport.StagePlan.class);
        verify(support).runHITLStage(eq(request), eq(null), planCaptor.capture());
        AgentOrchestratorSupport.StagePlan plan = planCaptor.getValue();
        assertThat(plan.action()).isEqualTo("review-action");
        assertThat(plan.assets()).hasSize(2);
        assertThat(plan.assets().getFirst().get("imageUrls"))
                .isEqualTo(List.of("/api/files/images/a.png", "/api/files/images/b.png"));
        assertThat(plan.assets().get(1).get("imageUrls"))
                .isEqualTo(List.of("/api/files/images/c.png"));
    }

    private static Scene scene(String id, int number, String imageUrls) {
        Scene scene = new Scene();
        scene.setId(id);
        scene.setSceneNumber(number);
        scene.setScriptContent("分镜" + number);
        scene.setImageUrls(imageUrls);
        scene.setImageUrl(imageUrls == null ? null : imageUrls.split(",")[0]);
        return scene;
    }

    private static OrchestrationRequest request() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setProjectId("p1");
        return new OrchestrationRequest(conversation, "", List.of(), null);
    }
}
