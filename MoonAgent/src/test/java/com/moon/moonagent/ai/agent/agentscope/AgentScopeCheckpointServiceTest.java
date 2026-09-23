package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.entity.AgentCheckpoint;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentCheckpointMapper;
import io.agentscope.core.message.ToolUseBlock;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentScopeCheckpointServiceTest {

    @Test
    void externalInputPauseCreatesOwnedCheckpointAndResolvesCustomText() {
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        AgentCheckpointMapper mapper = mock(AgentCheckpointMapper.class);
        when(support.runHITLStage(any(), any(), any())).thenReturn("token-1");
        AgentCheckpoint persisted = new AgentCheckpoint();
        persisted.setId("cp-1");
        persisted.setFormToken("token-1");
        persisted.setStatus("pending");
        when(mapper.selectOne(any())).thenReturn(persisted);
        AgentScopeCheckpointService service = new AgentScopeCheckpointService(support, mapper);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        OrchestrationRequest request = new OrchestrationRequest(
                conversation, "", List.of(), null);
        ToolUseBlock call = ToolUseBlock.builder()
                .id("call-1")
                .name(AgentScopeInteractionTools.REQUEST_HUMAN_INPUT)
                .input(Map.of(
                        "prompt", "想要哪种风格？",
                        "allow_custom", true,
                        "options", List.of(Map.of("title", "写实", "value", "realistic"))))
                .build();

        String token = service.pauseForExternalInput(request, "reply-1", call);

        assertThat(token).isEqualTo("token-1");
        verify(mapper).update(any(), any());
        ArgumentCaptor<AgentOrchestratorSupport.StagePlan> stage =
                ArgumentCaptor.forClass(AgentOrchestratorSupport.StagePlan.class);
        verify(support).runHITLStage(any(), any(), stage.capture());
        assertThat(stage.getValue().actions())
                .extracting(action -> action.get("id"))
                .containsExactly("option-0", "custom");

        AgentCheckpoint checkpoint = new AgentCheckpoint();
        checkpoint.setPlan("""
                {"items":[{"toolCallId":"call-1","toolName":"request_human_input",
                "options":[{"title":"写实","value":"realistic"}]}]}
                """);
        AgentScopeCheckpointService.ResumeValue value =
                service.resolve(checkpoint, "custom", "电影感写实");
        assertThat(value.toolCallId()).isEqualTo("call-1");
        assertThat(value.text()).isEqualTo("电影感写实");
    }

    @Test
    void confirmationUsesChineseToolLabelsAndMarksAgentScopeOwner() {
        AgentOrchestratorSupport support = mock(AgentOrchestratorSupport.class);
        AgentCheckpointMapper mapper = mock(AgentCheckpointMapper.class);
        when(support.runHITLStage(any(), any(), any())).thenReturn("card text");
        AgentCheckpoint persisted = new AgentCheckpoint();
        persisted.setId("cp-2");
        persisted.setFormToken("token-2");
        persisted.setStatus("pending");
        when(mapper.selectOne(any())).thenReturn(persisted);
        AgentScopeCheckpointService service = new AgentScopeCheckpointService(support, mapper);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        OrchestrationRequest request = new OrchestrationRequest(conversation, "", List.of(), null);
        ToolUseBlock call = ToolUseBlock.builder()
                .id("call-2")
                .name(AgentScopeImageTools.GENERATE_IMAGE_BATCH)
                .input(Map.of("prompt", "两张篮球图"))
                .build();

        String token = service.pauseForConfirmation(request, "reply-2", List.of(call));

        assertThat(token).isEqualTo("token-2");
        ArgumentCaptor<AgentOrchestratorSupport.StagePlan> stage =
                ArgumentCaptor.forClass(AgentOrchestratorSupport.StagePlan.class);
        verify(support).runHITLStage(any(), any(), stage.capture());
        assertThat(stage.getValue().planText())
                .contains("批量生成图片")
                .doesNotContain(AgentScopeImageTools.GENERATE_IMAGE_BATCH);
        ArgumentCaptor<UpdateWrapper<AgentCheckpoint>> update =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(mapper).update(any(), update.capture());
        assertThat(update.getValue().getSqlSet()).contains("owner_engine");
    }
}
