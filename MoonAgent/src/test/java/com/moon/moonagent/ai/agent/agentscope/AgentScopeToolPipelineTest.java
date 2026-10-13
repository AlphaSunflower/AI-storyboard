package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.audit.ToolExecutionTemplate;
import com.moon.moonagent.ai.agent.audit.ToolInvocationLedger;
import com.moon.moonagent.ai.agent.agentscope.workflow.AgentWorkflowService;
import com.moon.moonagent.ai.agent.CanvasSessionContext;
import com.moon.moonagent.ai.agent.AgentGenerationPreferences;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentToolInvocation;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentScopeToolPipelineTest {

    @Test
    void wrappedScopeToolStartsAndFinishesOneInvocation() {
        ToolInvocationLedger ledger = mock(ToolInvocationLedger.class);
        AgentToolInvocation record = new AgentToolInvocation();
        record.setId("inv-1");
        when(ledger.start(any(), any())).thenReturn(record);
        ToolExecutionTemplate template = mock(ToolExecutionTemplate.class);
        MediaToolPreflightService preflight = mock(MediaToolPreflightService.class);
        AgentScopeToolPipeline pipeline = new AgentScopeToolPipeline(
                ledger, template, preflight, new GenerationPreferenceDefaults(),
                mock(AgentWorkflowService.class));
        AgentScopeTool tool = new AgentScopeTool(
                "read_test", "test", AgentScopeTool.schema(Map.of(), List.of()),
                true, true, false,
                (input, param) -> Map.of("ok", true));

        var response = pipeline.wrap(tool).callAsync(ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock("call-1", "read_test", Map.of(), "{}", Map.of(), null))
                .input(Map.of())
                .build()).block();

        assertThat(response).isNotNull();
        verify(ledger, times(1)).start(any(), any());
        verify(template, times(1)).finish(any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void rejectsStoryboardToolsInsideCanvasEditTurn() {
        ToolInvocationLedger ledger = mock(ToolInvocationLedger.class);
        AgentScopeToolPipeline pipeline = new AgentScopeToolPipeline(
                ledger, mock(ToolExecutionTemplate.class),
                mock(MediaToolPreflightService.class), new GenerationPreferenceDefaults(),
                mock(AgentWorkflowService.class));
        AgentScopeTool tool = new AgentScopeTool(
                AgentScopeStoryboardTools.START_STORYBOARD, "test",
                AgentScopeTool.schema(Map.of(), List.of()),
                false, false, false,
                (input, param) -> Map.of("ok", true));

        var response = pipeline.wrap(tool).callAsync(
                param(AgentScopeStoryboardTools.START_STORYBOARD, canvasEditRuntime())).block();

        assertThat(text(response)).contains("CANVAS_MODE_STORYBOARD_UNAVAILABLE");
        verify(ledger, never()).start(any(), any());
    }

    @Test
    void keepsStoryboardToolsAvailableOutsideCanvasEditTurn() {
        ToolInvocationLedger ledger = mock(ToolInvocationLedger.class);
        AgentToolInvocation record = new AgentToolInvocation();
        record.setId("inv-2");
        when(ledger.start(any(), any())).thenReturn(record);
        AgentScopeToolPipeline pipeline = new AgentScopeToolPipeline(
                ledger, mock(ToolExecutionTemplate.class),
                mock(MediaToolPreflightService.class), new GenerationPreferenceDefaults(),
                mock(AgentWorkflowService.class));
        AgentScopeTool tool = new AgentScopeTool(
                AgentScopeStoryboardTools.START_STORYBOARD, "test",
                AgentScopeTool.schema(Map.of(), List.of()),
                false, false, false,
                (input, param) -> Map.of("ok", true));

        var response = pipeline.wrap(tool).callAsync(
                param(AgentScopeStoryboardTools.START_STORYBOARD, RuntimeContext.empty())).block();

        assertThat(text(response)).contains("\"ok\":true");
        verify(ledger, times(1)).start(any(), any());
    }

    @Test
    void fillsGenerationPreferencesIntoMediaToolInput() {
        ToolInvocationLedger ledger = mock(ToolInvocationLedger.class);
        AgentToolInvocation record = new AgentToolInvocation();
        record.setId("inv-3");
        when(ledger.start(any(), any())).thenReturn(record);
        AgentScopeToolPipeline pipeline = new AgentScopeToolPipeline(
                ledger, mock(ToolExecutionTemplate.class),
                mock(MediaToolPreflightService.class), new GenerationPreferenceDefaults(),
                mock(AgentWorkflowService.class));
        List<Map<String, Object>> seen = new java.util.ArrayList<>();
        AgentScopeTool tool = new AgentScopeTool(
                AgentScopeImageTools.GENERATE_IMAGE, "test",
                AgentScopeTool.schema(Map.of(), List.of()),
                false, false, false,
                (input, call) -> {
                    seen.add(input);
                    return Map.of("ok", true);
                });

        pipeline.wrap(tool).callAsync(toolCall(
                AgentScopeImageTools.GENERATE_IMAGE, Map.of("prompt", "一只猫"),
                runtimeWithPreferences(AgentGenerationPreferences.of(false, false,
                        Map.of("size", "2K", "aspectRatio", "16:9"), Map.of())))).block();

        assertThat(seen).hasSize(1);
        assertThat(seen.getFirst()).containsEntry("size", "2K")
                .containsEntry("aspect_ratio", "16:9")
                .containsEntry("prompt", "一只猫");
    }

    @Test
    void keepsModelProvidedMediaParamsOverPreferences() {
        ToolInvocationLedger ledger = mock(ToolInvocationLedger.class);
        AgentToolInvocation record = new AgentToolInvocation();
        record.setId("inv-4");
        when(ledger.start(any(), any())).thenReturn(record);
        AgentScopeToolPipeline pipeline = new AgentScopeToolPipeline(
                ledger, mock(ToolExecutionTemplate.class),
                mock(MediaToolPreflightService.class), new GenerationPreferenceDefaults(),
                mock(AgentWorkflowService.class));
        List<Map<String, Object>> seen = new java.util.ArrayList<>();
        AgentScopeTool tool = new AgentScopeTool(
                AgentScopeImageTools.GENERATE_IMAGE, "test",
                AgentScopeTool.schema(Map.of(), List.of()),
                false, false, false,
                (input, call) -> {
                    seen.add(input);
                    return Map.of("ok", true);
                });

        pipeline.wrap(tool).callAsync(toolCall(
                AgentScopeImageTools.GENERATE_IMAGE,
                Map.of("prompt", "一只猫", "size", "4K"),
                runtimeWithPreferences(AgentGenerationPreferences.of(false, false,
                        Map.of("size", "2K"), Map.of())))).block();

        assertThat(seen.getFirst()).containsEntry("size", "4K");
    }

    private static ToolCallParam toolCall(String toolName, Map<String, Object> input,
                                          RuntimeContext runtimeContext) {
        return ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock("call-media", toolName, Map.of(), "{}", Map.of(), null))
                .input(input)
                .runtimeContext(runtimeContext)
                .build();
    }

    private static RuntimeContext runtimeWithPreferences(AgentGenerationPreferences preferences) {
        AgentConversation conversation = mock(AgentConversation.class);
        OrchestrationRequest request = new OrchestrationRequest(conversation, "来张图", List.of(), null);
        request.setGenerationPreferences(preferences);
        return RuntimeContext.builder()
                .put(AgentScopeRunContext.class,
                        new AgentScopeRunContext(conversation, request, null, new AgentScopeTurnState()))
                .build();
    }

    private static ToolCallParam param(String toolName, RuntimeContext runtimeContext) {
        return ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock("call-1", toolName, Map.of(), "{}", Map.of(), null))
                .input(Map.of())
                .runtimeContext(runtimeContext)
                .build();
    }

    private static RuntimeContext canvasEditRuntime() {
        AgentConversation conversation = mock(AgentConversation.class);
        OrchestrationRequest request = new OrchestrationRequest(conversation, "画布任务", List.of(), null);
        request.setCanvasContext(new CanvasSessionContext(
                CanvasSessionContext.EDIT_MODE, "project-1", "canvas-1", List.of()));
        return RuntimeContext.builder()
                .put(AgentScopeRunContext.class,
                        new AgentScopeRunContext(conversation, request, null, new AgentScopeTurnState()))
                .build();
    }

    private static String text(io.agentscope.core.message.ToolResultBlock response) {
        assertThat(response).isNotNull();
        return ((TextBlock) response.getOutput().getFirst()).getText();
    }
}
