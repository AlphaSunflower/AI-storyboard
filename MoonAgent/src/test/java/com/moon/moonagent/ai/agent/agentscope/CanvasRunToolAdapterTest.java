package com.moon.moonagent.ai.agent.agentscope;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.entity.AgentConversation;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CanvasRunToolAdapterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void injectsRunScopeAndUpdatesRunFromGraphResult() throws Exception {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("canvas_graph_create_nodes");
        when(definition.description()).thenReturn("create nodes");
        when(definition.inputSchema()).thenReturn("{\"type\":\"object\",\"properties\":{}}");
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(any())).thenReturn(
                "{\"ok\":true,\"revision\":2,\"nodeCount\":1,\"edgeCount\":0,\"progress\":40}");
        AgentCanvasRunService runs = mock(AgentCanvasRunService.class);
        when(runs.findOperationResult("run-1", "run-1:call-1")).thenReturn(null);
        when(runs.recordGraphResult(eq("run-1"), eq("run-1:call-1"), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        AgentScopeTurnState turnState = new AgentScopeTurnState();
        turnState.setActiveCanvasRun("run-1", "canvas-1");
        RuntimeContext runtime = RuntimeContext.builder()
                .userId("user-1")
                .sessionId("conversation-1")
                .put(AgentScopeRunContext.class, new AgentScopeRunContext(
                        conversation(), null, null, turnState))
                .build();
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder()
                        .id("call-1")
                        .name("canvas_graph_create_nodes")
                        .input(Map.of("items", List.of(Map.of("kind", "text"))))
                        .build())
                .runtimeContext(runtime)
                .build();
        CanvasRunToolAdapter adapter = new CanvasRunToolAdapter(callback, runs);

        adapter.callAsync(param).block();

        var inputCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(callback).call(inputCaptor.capture());
        Map<String, Object> injected = JSON.readValue(
                inputCaptor.getValue(), new TypeReference<>() {});
        assertThat(injected)
                .containsEntry("userId", "user-1")
                .containsEntry("projectId", "project-1")
                .containsEntry("canvasId", "canvas-1")
                .containsEntry("runId", "run-1")
                .containsEntry("operationKey", "run-1:call-1");
        verify(runs).recordGraphResult(eq("run-1"), eq("run-1:call-1"), any());
    }

    @Test
    void injectsRunScopeIntoBrowserExecuteTools() throws Exception {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("canvas_execute_node");
        when(definition.description()).thenReturn("execute node");
        when(definition.inputSchema()).thenReturn("{\"type\":\"object\",\"properties\":{}}");
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(any())).thenReturn("{\"ok\":true,\"jobId\":\"job-1\"}");
        AgentCanvasRunService runs = mock(AgentCanvasRunService.class);
        when(runs.findOperationResult("run-1", "run-1:call-1")).thenReturn(null);
        when(runs.recordGraphResult(eq("run-1"), eq("run-1:call-1"), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        AgentScopeTurnState turnState = new AgentScopeTurnState();
        turnState.setActiveCanvasRun("run-1", "canvas-1");
        RuntimeContext runtime = RuntimeContext.builder()
                .userId("user-1")
                .sessionId("conversation-1")
                .put(AgentScopeRunContext.class, new AgentScopeRunContext(
                        conversation(), null, null, turnState))
                .build();
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder()
                        .id("call-1")
                        .name("canvas_execute_node")
                        .input(Map.of("nodeId", "node-1", "actionId", "generate"))
                        .build())
                .input(Map.of("nodeId", "node-1", "actionId", "generate"))
                .runtimeContext(runtime)
                .build();
        CanvasRunToolAdapter adapter = new CanvasRunToolAdapter(callback, runs);

        adapter.callAsync(param).block();

        var inputCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(callback).call(inputCaptor.capture());
        Map<String, Object> injected = JSON.readValue(
                inputCaptor.getValue(), new TypeReference<>() {});
        assertThat(injected)
                .containsEntry("userId", "user-1")
                .containsEntry("projectId", "project-1")
                .containsEntry("canvasId", "canvas-1")
                .containsEntry("runId", "run-1")
                .containsEntry("operationKey", "run-1:call-1")
                .containsEntry("nodeId", "node-1")
                .containsEntry("actionId", "generate");
        verify(runs).recordGraphResult(eq("run-1"), eq("run-1:call-1"), any());
    }

    @Test
    void requiresCanvasRunBeforeExecutingInBrowser() {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("canvas_execute_graph");
        when(definition.description()).thenReturn("execute graph");
        when(definition.inputSchema()).thenReturn("{\"type\":\"object\",\"properties\":{}}");
        when(callback.getToolDefinition()).thenReturn(definition);
        AgentCanvasRunService runs = mock(AgentCanvasRunService.class);
        AgentScopeTurnState turnState = new AgentScopeTurnState();
        RuntimeContext runtime = RuntimeContext.builder()
                .userId("user-1")
                .sessionId("conversation-1")
                .put(AgentScopeRunContext.class, new AgentScopeRunContext(
                        conversation(), null, null, turnState))
                .build();
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder()
                        .id("call-1")
                        .name("canvas_execute_graph")
                        .input(Map.of())
                        .build())
                .runtimeContext(runtime)
                .build();
        CanvasRunToolAdapter adapter = new CanvasRunToolAdapter(callback, runs);

        adapter.callAsync(param).block();

        verify(callback, org.mockito.Mockito.never()).call(any());
    }

    @Test
    void recoversActiveRunFromTurnAfterHitlResume() throws Exception {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("canvas_graph_get_context");
        when(definition.description()).thenReturn("get context");
        when(definition.inputSchema()).thenReturn("{\"type\":\"object\",\"properties\":{}}");
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(any())).thenReturn("{\"ok\":true,\"revision\":4}");
        AgentCanvasRunService runs = mock(AgentCanvasRunService.class);
        when(runs.activeByTurn("turn-1")).thenReturn(new AgentCanvasRunService.CanvasRunView(
                "run-1", "conversation-1", "project-1", "turn-1",
                "canvas-1", "画布", "running", 30,
                2, 1, 4, List.of(), null, OffsetDateTime.now(), null));
        when(runs.findOperationResult("run-1", "run-1:call-1")).thenReturn(null);
        when(runs.recordGraphResult(eq("run-1"), eq("run-1:call-1"), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        AgentScopeTurnState turnState = new AgentScopeTurnState();
        OrchestrationRequest request = new OrchestrationRequest(
                conversation(), "", List.of(), null);
        request.setTurnId("turn-1");
        RuntimeContext runtime = RuntimeContext.builder()
                .userId("user-1")
                .sessionId("conversation-1")
                .put(AgentScopeRunContext.class, new AgentScopeRunContext(
                        conversation(), request, null, turnState))
                .build();
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder()
                        .id("call-1")
                        .name("canvas_graph_get_context")
                        .input(Map.of())
                        .build())
                .runtimeContext(runtime)
                .build();
        CanvasRunToolAdapter adapter = new CanvasRunToolAdapter(callback, runs);

        adapter.callAsync(param).block();

        var inputCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(callback).call(inputCaptor.capture());
        Map<String, Object> injected = JSON.readValue(
                inputCaptor.getValue(), new TypeReference<>() {});
        assertThat(injected)
                .containsEntry("runId", "run-1")
                .containsEntry("canvasId", "canvas-1");
        assertThat(turnState.activeCanvasRunId()).isEqualTo("run-1");
    }

    @Test
    void parsesMcpTextContentWrapperAndUpdatesCounts() throws Exception {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("canvas_graph_create_nodes");
        when(definition.description()).thenReturn("create nodes");
        when(definition.inputSchema()).thenReturn("{\"type\":\"object\",\"properties\":{}}");
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(any())).thenReturn(
                "[{\"type\":\"text\",\"text\":\"{\\\"ok\\\":true,\\\"revision\\\":7,"
                        + "\\\"nodeCount\\\":3,\\\"edgeCount\\\":2,\\\"progress\\\":65}\"}]");
        AgentCanvasRunService runs = mock(AgentCanvasRunService.class);
        when(runs.findOperationResult("run-1", "run-1:call-1")).thenReturn(null);
        when(runs.recordGraphResult(eq("run-1"), eq("run-1:call-1"), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        AgentScopeTurnState turnState = new AgentScopeTurnState();
        turnState.setActiveCanvasRun("run-1", "canvas-1");
        RuntimeContext runtime = RuntimeContext.builder()
                .userId("user-1")
                .sessionId("conversation-1")
                .put(AgentScopeRunContext.class, new AgentScopeRunContext(
                        conversation(), null, null, turnState))
                .build();
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder()
                        .id("call-1")
                        .name("canvas_graph_create_nodes")
                        .input(Map.of("items", List.of(Map.of("type", "text"))))
                        .build())
                .runtimeContext(runtime)
                .build();
        CanvasRunToolAdapter adapter = new CanvasRunToolAdapter(callback, runs);

        adapter.callAsync(param).block();

        var resultCaptor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(runs).recordGraphResult(eq("run-1"), eq("run-1:call-1"), resultCaptor.capture());
        assertThat(resultCaptor.getValue())
                .containsEntry("revision", 7)
                .containsEntry("nodeCount", 3)
                .containsEntry("edgeCount", 2)
                .containsEntry("progress", 65);
    }

    private static AgentConversation conversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setProjectId("project-1");
        return conversation;
    }
}
