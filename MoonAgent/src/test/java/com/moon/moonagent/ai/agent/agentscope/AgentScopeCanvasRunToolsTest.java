package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentScopeCanvasRunToolsTest {

    @Test
    void createRunStoresActiveCanvasInTurnState() {
        AgentCanvasRunService runs = mock(AgentCanvasRunService.class);
        when(runs.create(
                any(AgentConversation.class), eq("turn-1"), eq("call-1"),
                eq("雪茄房转场"), eq("搭建画布链路"), anyList()))
                .thenReturn(new AgentCanvasRunService.CanvasRunView(
                        "run-1", "conversation-1", "project-1", "turn-1",
                        "canvas-1", "雪茄房转场", "running", 0,
                        0, 0, 0, List.of(), null, OffsetDateTime.now(), null));
        AgentScopeTurnState turnState = new AgentScopeTurnState();
        OrchestrationRequest request = new OrchestrationRequest(
                conversation(), "", List.of(), null);
        request.setTurnId("turn-1");
        AgentScopeRunContext runContext = new AgentScopeRunContext(
                conversation(), request, null, turnState);
        RuntimeContext runtime = RuntimeContext.builder()
                .userId("user-1")
                .sessionId("conversation-1")
                .put(AgentScopeRunContext.class, runContext)
                .build();
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder()
                        .id("call-1")
                        .name("canvas_create_run")
                        .input(Map.of())
                        .build())
                .runtimeContext(runtime)
                .build();
        AgentScopeCanvasRunTools tools = new AgentScopeCanvasRunTools(runs);

        Object result = tools.tool().invoke(Map.of(
                "title", "雪茄房转场",
                "intent", "搭建画布链路"), param);

        assertThat(String.valueOf(result)).contains("run-1").contains("canvas-1");
        assertThat(turnState.activeCanvasRunId()).isEqualTo("run-1");
        assertThat(turnState.activeCanvasId()).isEqualTo("canvas-1");
    }

    private static AgentConversation conversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setProjectId("project-1");
        return conversation;
    }
}
