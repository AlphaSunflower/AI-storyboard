package com.moon.moonagent.ai.agent.impl;

import com.moon.moonagent.ai.agent.AgentTurnResult;
import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
import com.moon.moonagent.ai.agent.orchestration.UnifiedAgentLoop;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentCheckpointMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentOrchestratorImplEngineTest {

    @Mock UnifiedAgentLoop unifiedAgentLoop;
    @Mock AgentCheckpointMapper checkpointMapper;
    @Mock AgentOrchestratorSupport support;
    @Mock SseEmitter emitter;

    @Test
    void legacyConversationIsReadOnly() {
        AgentOrchestratorImpl orchestrator = new AgentOrchestratorImpl(
                unifiedAgentLoop, checkpointMapper, support);
        AgentConversation conversation = conversation("LEGACY");

        AgentTurnResult result = orchestrator.runTurn(conversation, "hello", List.of(), List.of(),
                "", false, null, List.of(), List.of(), emitter);

        assertThat(result.content()).contains("旧版会话已只读归档");
        verify(unifiedAgentLoop, never()).runTurn(any(), anyString(), any(), any(), anyString(), anyBoolean(),
                any(), any(), any(), any());
    }

    @Test
    void unifiedConversationOnlyRunsUnifiedLoop() {
        AgentOrchestratorImpl orchestrator = new AgentOrchestratorImpl(
                unifiedAgentLoop, checkpointMapper, support);
        AgentConversation conversation = conversation("UNIFIED_LOOP");
        when(unifiedAgentLoop.runTurn(any(), anyString(), any(), any(), anyString(), anyBoolean(),
                any(), any(), any(), any())).thenReturn(AgentTurnResult.done("unified"));

        AgentTurnResult result = orchestrator.runTurn(conversation, "hello", List.of(), List.of(),
                "", false, null, List.of(), List.of(), emitter);

        assertThat(result.metadata().get("orchestrationMode")).isEqualTo("UNIFIED_LOOP");
    }

    @Test
    void legacyCheckpointCannotResume() {
        AgentOrchestratorImpl orchestrator = new AgentOrchestratorImpl(
                unifiedAgentLoop, checkpointMapper, support);
        AgentConversation conversation = conversation("UNIFIED_LOOP");
        AgentCheckpoint checkpoint = new AgentCheckpoint();
        checkpoint.setId("cp1");
        checkpoint.setFormToken("token");
        checkpoint.setConversationId(conversation.getId());
        checkpoint.setAction("video-role-select");
        checkpoint.setOwnerEngine("LEGACY");
        checkpoint.setStatus("pending");
        checkpoint.setExpirationTime(OffsetDateTime.now().plusMinutes(10));
        checkpoint.setPlan("{}");
        when(checkpointMapper.selectOne(any())).thenReturn(checkpoint);

        AgentTurnResult result = orchestrator.resumeTurn(conversation, "token", "role", "",
                Map.of(), List.of(), "", null, emitter);

        assertThat(result.content()).isEmpty();
        verify(checkpointMapper, never()).update(any(), any());
        verify(unifiedAgentLoop, never()).resumeTurn(any(), any(), any(), anyString(), any(), any(),
                anyString(), any(), any());
    }

    private static AgentConversation conversation(String mode) {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setUserId("u1");
        conversation.setProjectId("p1");
        conversation.setOrchestrationMode(mode);
        conversation.setEngineVersion("test-v1");
        return conversation;
    }
}
