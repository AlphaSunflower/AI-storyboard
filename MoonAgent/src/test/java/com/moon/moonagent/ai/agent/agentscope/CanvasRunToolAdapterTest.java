package com.moon.moonagent.ai.agent.agentscope;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.CanvasSessionContext;
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
    void adoptsCurrentCanvasWhenCanvasEditTurnHasNoRun() throws Exception {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("canvas_graph_get_context");
        when(definition.description()).thenReturn("get context");
        when(definition.inputSchema()).thenReturn("{\"type\":\"object\",\"properties\":{}}");
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(any())).thenReturn("{\"ok\":true,\"revision\":42}");
        AgentCanvasRunService runs = mock(AgentCanvasRunService.class);
        when(runs.findOperationResult("run-edit", "run-edit:call-1")).thenReturn(null);
        when(runs.recordGraphResult(eq("run-edit"), eq("run-edit:call-1"), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(runs.adoptCurrentCanvas(any(), eq("turn-1"), eq("canvas-edit-1")))
                .thenReturn(new AgentCanvasRunService.CanvasRunView(
                        "run-edit", "conversation-1", "project-1", "turn-1",
                        "canvas-edit-1", "画布", "running", 0,
                        0, 0, 0, List.of(), null, OffsetDateTime.now(), null));
        AgentScopeTurnState turnState = new AgentScopeTurnState();
        OrchestrationRequest request = new OrchestrationRequest(
                conversation(), "", List.of(), null);
        request.setTurnId("turn-1");
        request.setCanvasContext(new CanvasSessionContext(
                CanvasSessionContext.EDIT_MODE, "project-1", "canvas-edit-1", List.of()));
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

        // 画布编辑模式：直接在用户当前画布上作业，不再回 CANVAS_RUN_REQUIRED
        var inputCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(callback).call(inputCaptor.capture());
        Map<String, Object> injected = JSON.readValue(
                inputCaptor.getValue(), new TypeReference<>() {});
        assertThat(injected)
                .containsEntry("runId", "run-edit")
                .containsEntry("canvasId", "canvas-edit-1");
        assertThat(turnState.activeCanvasRunId()).isEqualTo("run-edit");
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

    @Test
    void hidesInjectedParamsFromModelVisibleSchema() {
        ToolCallback callback = canvasCallback("canvas_graph_apply_plan", """
                {"type":"object","properties":{
                  "userId":{"type":"string"},"projectId":{"type":"string"},"canvasId":{"type":"string"},
                  "runId":{"type":"string"},"operationKey":{"type":"string"},
                  "plan":{"type":"object"},"edges":{"type":"array"}},
                 "required":["projectId","plan"]}""");

        CanvasRunToolAdapter adapter = new CanvasRunToolAdapter(
                callback, mock(AgentCanvasRunService.class));

        Map<String, Object> schema = adapter.getParameters();
        assertThat(properties(schema))
                .doesNotContainKeys("userId", "projectId", "canvasId", "runId", "operationKey")
                .containsKeys("plan", "edges");
        // required 里的注入型参数也要剔除，业务必填项必须保留
        assertThat(required(schema)).containsExactly("plan");
        assertThat(schema).containsEntry("type", "object");
    }

    @Test
    void keepsInjectedParamsWhenHideSwitchIsOff() {
        ToolCallback callback = canvasCallback("canvas_graph_create_nodes", """
                {"type":"object","properties":{"userId":{"type":"string"},"items":{"type":"array"}},
                 "required":["items"]}""");

        CanvasRunToolAdapter adapter = new CanvasRunToolAdapter(
                callback, mock(AgentCanvasRunService.class), false);

        assertThat(properties(adapter.getParameters())).containsKeys("userId", "items");
    }

    @Test
    void hideInjectedParamsToleratesMissingPropertiesAndEmptySchema() {
        assertThat(CanvasRunToolAdapter.hideInjectedParams(null))
                .containsEntry("type", "object");
        assertThat(CanvasRunToolAdapter.hideInjectedParams(Map.of()))
                .containsEntry("type", "object");
        assertThat(properties(CanvasRunToolAdapter.hideInjectedParams(
                Map.of("type", "object", "required", List.of("projectId")))))
                .isEmpty();
        assertThat(required(CanvasRunToolAdapter.hideInjectedParams(
                Map.of("type", "object", "required", List.of("projectId")))))
                .isEmpty();
    }

    @Test
    void relaxesStrictAdditionalPropertiesOnlyWhenSomethingWasRemoved() {
        // 模型若凭旧记忆仍传 userId，声明里已无该属性：严格 additionalProperties:false 会让
        // 以前"被静默覆盖"的调用变成硬失败，因此真删了属性就放宽。
        Map<String, Object> withInjected = CanvasRunToolAdapter.hideInjectedParams(Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of("userId", Map.of("type", "string"), "items", Map.of("type", "array"))));
        assertThat(withInjected).containsEntry("additionalProperties", true);

        Map<String, Object> withoutInjected = CanvasRunToolAdapter.hideInjectedParams(Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of("items", Map.of("type", "array"))));
        assertThat(withoutInjected).containsEntry("additionalProperties", false);

        Map<String, Object> schemaTypedAdditional = CanvasRunToolAdapter.hideInjectedParams(Map.of(
                "type", "object",
                "additionalProperties", Map.of("type", "string"),
                "properties", Map.of("runId", Map.of("type", "string"))));
        assertThat(schemaTypedAdditional.get("additionalProperties")).isInstanceOf(Map.class);
    }

    @Test
    void modelSuppliedInternalParamsAreOverwrittenByInjection() throws Exception {
        ToolCallback callback = canvasCallback("canvas_graph_get_context",
                "{\"type\":\"object\",\"properties\":{}}");
        when(callback.call(any())).thenReturn("{\"ok\":true,\"revision\":1}");
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
        // 模型自己编了内部字段：注入必须覆盖，不能让 "default" 落到服务端
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder()
                        .id("call-1")
                        .name("canvas_graph_get_context")
                        .input(Map.of("userId", "default", "projectId", "guessed"))
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
                .containsEntry("projectId", "project-1");
    }

    private static ToolCallback canvasCallback(String name, String schema) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(definition.description()).thenReturn(name);
        when(definition.inputSchema()).thenReturn(schema);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }

    @SuppressWarnings("unchecked")
    private static List<String> required(Map<String, Object> schema) {
        return (List<String>) schema.get("required");
    }

    private static AgentConversation conversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setProjectId("project-1");
        return conversation;
    }
}
