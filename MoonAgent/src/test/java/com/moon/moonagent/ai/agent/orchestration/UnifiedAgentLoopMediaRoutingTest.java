package com.moon.moonagent.ai.agent.orchestration;

import com.moon.moonagent.ai.agent.IntentRecognitionService;
import com.moon.moonagent.ai.agent.AgentTurnResult;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
import com.moon.moonagent.ai.agent.skill.SkillManager;
import com.moon.moonagent.ai.agent.specialist.SpecialistAgent;
import com.moon.moonagent.ai.agent.specialist.SpecialistAgentRegistry;
import com.moon.moonagent.ai.agent.specialist.SpecialistResult;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentLoopRun;
import com.moon.moonagent.mapper.AgentMessageMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnifiedAgentLoopMediaRoutingTest {

    @Mock IntentRecognitionService intentRecognitionService;
    @Mock AgentMessageMapper messageMapper;
    @Mock AgentOrchestratorSupport support;
    @Mock SkillManager skillManager;
    @Mock SpecialistAgentRegistry specialistRegistry;
    @Mock OrchestrationRunRecorder recorder;
    @Mock AigcGatewaySpecialist aigcGatewaySpecialist;
    @Mock SpecialistAgent specialist;
    @Mock SseEmitter emitter;

    @Test
    void pictureRequestsUseTheExistingSkillFlowByDefault() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setUserId("u1");
        when(specialist.execute(any())).thenReturn(SpecialistResult.done("skill-plan"));
        AgentTurnResult result = runMediaTurn(conversation, false);

        verify(specialist).execute(any());
        verify(aigcGatewaySpecialist, never()).execute(anyString(), any());
        org.assertj.core.api.Assertions.assertThat(result.content()).isEqualTo("skill-plan");
    }

    @Test
    void enabledPictureRequestsUseTheSkillDrivenGatewaySpecialist() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setUserId("u1");
        when(aigcGatewaySpecialist.execute(anyString(), any()))
                .thenReturn(SpecialistResult.done("skill-plan"));
        runMediaTurn(conversation, true);

        verify(aigcGatewaySpecialist).execute(anyString(), any());
        verify(specialist, never()).execute(any());
    }

    @Test
    void confirmedPictureRequestsUseGatewayMediaWhenExplicitlyEnabled() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setUserId("u1");
        AgentCheckpoint checkpoint = new AgentCheckpoint();
        checkpoint.setAction("generate_image");
        checkpoint.setPlan("{\"intent\":\"intent-pic\",\"prompt\":\"改成3D效果\"}");

        when(support.planField(anyString(), anyString())).thenReturn("把图片改成3D效果");
        when(support.planPicUrls(anyString())).thenReturn(List.of("http://localhost/a.png"));
        AgentLoopRun run = new AgentLoopRun();
        run.setId("run-2");
        run.setTraceId("trace-2");
        run.setEngineVersion("unified-loop-v1");
        when(recorder.start(anyString(), anyString(), any(), anyString(), any())).thenReturn(run);
        when(specialistRegistry.get("intent-pic")).thenReturn(Optional.of(specialist));
        when(skillManager.byIntent("intent-pic")).thenReturn(Optional.empty());
        when(aigcGatewaySpecialist.execute(anyString(), any())).thenReturn(SpecialistResult.done("gateway-result"));

        AgentTurnResult result = loopWithGatewayEnabled(true).resumeTurn(
                conversation, checkpoint, "generate_image", "", Map.of(), List.of(), "intent-pic",
                null, emitter);

        verify(aigcGatewaySpecialist).execute(anyString(), any());
        verify(specialist, never()).execute(any());
        org.assertj.core.api.Assertions.assertThat(result.content()).isEqualTo("gateway-result");
    }

    private AgentTurnResult runMediaTurn(AgentConversation conversation, boolean gatewayEnabled) {
        return loopWithGatewayEnabled(gatewayEnabled).runTurn(
                conversation, "把这张图卡通化", List.of("http://localhost/a.png"),
                List.of(), "intent-pic", false, null, List.of(), List.of(), emitter);
    }

    private UnifiedAgentLoop loopWithGatewayEnabled(boolean gatewayEnabled) {
        UnifiedAgentLoop loop = new UnifiedAgentLoop(
                intentRecognitionService, messageMapper, support, skillManager,
                specialistRegistry, recorder, aigcGatewaySpecialist);
        ReflectionTestUtils.setField(loop, "unifiedMediaGatewayEnabled", gatewayEnabled);
        AgentLoopRun run = new AgentLoopRun();
        run.setId("run-1");
        run.setTraceId("trace-1");
        run.setEngineVersion("unified-loop-v1");
        when(recorder.start(anyString(), anyString(), any(), anyString(), any())).thenReturn(run);
        when(specialistRegistry.get("intent-pic")).thenReturn(Optional.of(specialist));
        when(skillManager.byIntent("intent-pic")).thenReturn(Optional.empty());
        return loop;
    }
}
