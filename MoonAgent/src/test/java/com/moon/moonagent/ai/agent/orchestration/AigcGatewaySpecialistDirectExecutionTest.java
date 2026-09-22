package com.moon.moonagent.ai.agent.orchestration;

import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
import com.moon.moonagent.ai.agent.handler.AgentTraceAccumulator;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.agent.skill.AgentSkill;
import com.moon.moonagent.ai.agent.skill.SkillExpander;
import com.moon.moonagent.ai.agent.specialist.SpecialistContext;
import com.moon.moonagent.ai.agent.specialist.SpecialistResult;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AigcGatewaySpecialistDirectExecutionTest {

    @Test
    void directExecutionBypassesHitlCardConstruction() {
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        SkillExpander skillExpander = mock(SkillExpander.class);
        MediaGenerationCoordinator coordinator = mock(MediaGenerationCoordinator.class);
        when(skillExpander.hasTool("generate_image")).thenReturn(false);
        AigcGatewaySpecialist specialist = new AigcGatewaySpecialist(
                mock(ChatClient.Builder.class),
                mock(GatewayModelService.class),
                mock(FileStorageService.class),
                skillExpander,
                coordinator,
                support);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        SpecialistContext context = new SpecialistContext(
                conversation, "生成一张PPT图片", List.of(), null, null,
                AgentSkill.of("pic", Map.of("description", "image"), Map.of()),
                "", "", Map.of(), List.of(), "", "", null, null, true);

        SpecialistResult result = specialist.execute("intent-pic", context);

        assertThat(result.needsUserInput()).isTrue();
        assertThat(result.message()).contains("请回复 1/2/3 选择方案");
        verify(support, never()).runHITLStage(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void selectedPlanPropagatesHitlStateToOuterTrace() {
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        SkillExpander skillExpander = mock(SkillExpander.class);
        MediaGenerationCoordinator coordinator = mock(MediaGenerationCoordinator.class);
        AigcGatewaySpecialist specialist = new AigcGatewaySpecialist(
                mock(ChatClient.Builder.class),
                mock(GatewayModelService.class),
                mock(FileStorageService.class),
                skillExpander,
                coordinator,
                support);
        AgentTraceAccumulator trace = mock(AgentTraceAccumulator.class);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        String questionState = """
                {"skill":"pic","originalMessage":"生成PPT图片","plansText":"【方案1】星际指挥舱\\n【方案2】月核觉醒\\n【方案3】光子玻璃","plans":[{"title":"星际指挥舱","summary":"深空 HUD","prompt":"完整提示词"}]}
                """;
        SpecialistContext context = new SpecialistContext(
                conversation, "1", List.of(), null, null,
                AgentSkill.of("pic", Map.of("description", "image"), Map.of()),
                "select_image:1", "", Map.of(), List.of(), "", questionState,
                trace, null, true);
        when(support.runHITLStage(any(OrchestrationRequest.class), any(), any()))
                .thenAnswer(invocation -> {
                    OrchestrationRequest request = invocation.getArgument(0);
                    request.setHitlPaused(true);
                    request.setHitlMessageId("message-1");
                    return "card";
                });

        SpecialistResult result = specialist.execute("intent-pic", context);

        assertThat(result.message()).isEqualTo("card");
        verify(trace).setHitlPaused(true);
        verify(trace).setHitlMessageId("message-1");
    }

    @Test
    void refinementInputKeepsOriginalGoalAndPreviousPlans() {
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        AigcGatewaySpecialist specialist = new AigcGatewaySpecialist(
                mock(ChatClient.Builder.class),
                mock(GatewayModelService.class),
                mock(FileStorageService.class),
                mock(SkillExpander.class),
                mock(MediaGenerationCoordinator.class),
                support);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        String questionState = """
                {"originalMessage":"生成科技感PPT主视觉，再做动画视频","plansText":"【方案1】星际指挥舱\\n【方案2】月核觉醒"}
                """;
        SpecialistContext context = new SpecialistContext(
                conversation, "请加强电影感", List.of(), null, null,
                AgentSkill.of("pic", Map.of("description", "image"), Map.of()),
                "refine", "请加强电影感", Map.of(), List.of(), "", questionState,
                null, null, true);

        String input = specialist.planningInput(context, "intent-pic");

        assertThat(input).contains("原始需求：生成科技感PPT主视觉，再做动画视频");
        assertThat(input).contains("上一轮方案：\n【方案1】星际指挥舱");
        assertThat(input).contains("用户调整意见：请加强电影感");
        assertThat(input).contains("保持任务主题不变");
    }

    @Test
    void missingMediaToolReturnsFailureInsteadOfCompletedMessage() {
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        SkillExpander skillExpander = mock(SkillExpander.class);
        when(skillExpander.hasTool("generate_image")).thenReturn(false);
        AigcGatewaySpecialist specialist = new AigcGatewaySpecialist(
                mock(ChatClient.Builder.class),
                mock(GatewayModelService.class),
                mock(FileStorageService.class),
                skillExpander,
                mock(MediaGenerationCoordinator.class),
                support);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        SpecialistContext context = new SpecialistContext(
                conversation, "1", List.of(), null, null,
                AgentSkill.of("pic", Map.of("description", "image"), Map.of()),
                "generate_image:1", "", Map.of(), List.of(), "generate_image",
                "{\"plans\":[{\"prompt\":\"prompt\"}]}", null, null, true);

        SpecialistResult result = specialist.execute("intent-pic", context);

        assertThat(result.isFailed()).isTrue();
        assertThat(result.message()).contains("媒体 MCP 工具尚未就绪");
    }

    @Test
    void finalFrameWorkflowUsesLastFrameReferenceRole() {
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        AigcGatewaySpecialist specialist = new AigcGatewaySpecialist(
                mock(ChatClient.Builder.class),
                mock(GatewayModelService.class),
                mock(FileStorageService.class),
                mock(SkillExpander.class),
                mock(MediaGenerationCoordinator.class),
                support);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        SpecialistContext context = new SpecialistContext(
                conversation,
                "参考上一张 PPT 图片生成前导动画，动画最后稳定落到该图并最终定格",
                List.of("/api/files/images/reference.png"), null, null,
                AgentSkill.of("video", Map.of("description", "video"), Map.of()),
                "generate_video:1", "", Map.of(), List.of(),
                "generate_video", "{\"plans\":[{\"message\":\"方案\"}]}",
                null, null, true);

        assertThat(specialist.referenceRoleFor(context)).isEqualTo("LAST_FRAME");
    }
}
