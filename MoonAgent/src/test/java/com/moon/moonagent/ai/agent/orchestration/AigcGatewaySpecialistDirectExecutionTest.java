package com.moon.moonagent.ai.agent.orchestration;

import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
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
}
