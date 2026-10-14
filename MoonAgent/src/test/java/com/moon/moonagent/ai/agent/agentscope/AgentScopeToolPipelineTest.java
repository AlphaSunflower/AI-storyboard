package com.moon.moonagent.ai.agent.agentscope;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.AgentInterruptException;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
                mock(AgentWorkflowService.class), new ToolCallStateGuard());
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
                mock(AgentWorkflowService.class), new ToolCallStateGuard());
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
                mock(AgentWorkflowService.class), new ToolCallStateGuard());
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
                mock(AgentWorkflowService.class), new ToolCallStateGuard());
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
                mock(AgentWorkflowService.class), new ToolCallStateGuard());
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

    /**
     * 画布模式的提前拒绝路径：守卫不参与（begin/complete 都不会发生），也不得抛异常。
     */
    @Test
    void canvasModeRejectionNeverTouchesTheLifecycleGuard() {
        ToolInvocationLedger ledger = mock(ToolInvocationLedger.class);
        ToolCallStateGuard guard = new ToolCallStateGuard();
        AgentScopeToolPipeline pipeline = new AgentScopeToolPipeline(
                ledger, mock(ToolExecutionTemplate.class),
                mock(MediaToolPreflightService.class), new GenerationPreferenceDefaults(),
                mock(AgentWorkflowService.class), guard);
        AgentScopeTool tool = new AgentScopeTool(
                AgentScopeStoryboardTools.START_STORYBOARD, "test",
                AgentScopeTool.schema(Map.of(), List.of()),
                false, false, false,
                (input, param) -> Map.of("ok", true));

        var response = pipeline.wrap(tool).callAsync(
                param(AgentScopeStoryboardTools.START_STORYBOARD, canvasEditRuntime())).block();

        assertThat(text(response)).contains("CANVAS_MODE_STORYBOARD_UNAVAILABLE");
        assertThat(guard.snapshot()).isEmpty();
        assertThat(guard.stateOf("", "call-1")).isNull();
    }

    /**
     * 媒体 preflight 短路（ASK）：工具本体不执行，但守卫必须由 executeMap 收口到终态，
     * 不能停在 RUNNING，也不能因为缺少 complete 而抛异常。
     */
    @Test
    void preflightAskShortCircuitStillReachesATerminalGuardState() {
        ToolInvocationLedger ledger = mock(ToolInvocationLedger.class);
        AgentToolInvocation record = new AgentToolInvocation();
        record.setId("inv-preflight");
        when(ledger.start(any(), any())).thenReturn(record);
        ToolCallStateGuard guard = new ToolCallStateGuard();
        MediaToolPreflightService preflight = new MediaToolPreflightService(
                new AgentScopeProperties(), null, null, new SimpleMeterRegistry());
        AgentScopeToolPipeline pipeline = new AgentScopeToolPipeline(
                ledger, mock(ToolExecutionTemplate.class), preflight,
                new GenerationPreferenceDefaults(), mock(AgentWorkflowService.class), guard);
        java.util.concurrent.atomic.AtomicBoolean invoked = new java.util.concurrent.atomic.AtomicBoolean();
        AgentScopeTool tool = new AgentScopeTool(
                AgentScopeImageTools.GENERATE_IMAGE, "test",
                AgentScopeTool.schema(Map.of(), List.of()),
                false, false, false,
                (input, call) -> {
                    invoked.set(true);
                    return Map.of("ok", true);
                });

        var response = pipeline.wrap(tool).callAsync(toolCall(
                AgentScopeImageTools.GENERATE_IMAGE, Map.of(),
                runtimeWithRequest(AgentGenerationPreferences.none(),
                        "帮我生成一套分镜图片，风格保持统一，先出一张看看"))).block();

        assertThat(invoked).isFalse();
        assertThat(text(response)).contains("\"needsClarification\":true");
        assertThat(guard.stateOf("", "call-media")).isEqualTo("NEEDS_CONFIRMATION");
        assertThat(guard.state("", "call-media").terminal()).isTrue();
    }

    /** 异常出口写入 ERROR 终态（不是 RUNNING，也不重复写入）。 */
    @Test
    void failingToolCompletesTheGuardInErrorTerminalState() {
        ToolInvocationLedger ledger = mock(ToolInvocationLedger.class);
        AgentToolInvocation record = new AgentToolInvocation();
        record.setId("inv-fail");
        when(ledger.start(any(), any())).thenReturn(record);
        ToolCallStateGuard guard = new ToolCallStateGuard();
        AgentScopeToolPipeline pipeline = new AgentScopeToolPipeline(
                ledger, mock(ToolExecutionTemplate.class),
                mock(MediaToolPreflightService.class), new GenerationPreferenceDefaults(),
                mock(AgentWorkflowService.class), guard);
        AgentScopeTool tool = new AgentScopeTool(
                "read_test", "test", AgentScopeTool.schema(Map.of(), List.of()),
                true, true, false,
                (input, param) -> {
                    throw new IllegalStateException("boom");
                });

        assertThatThrownBy(() -> pipeline.wrap(tool).callAsync(ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock("call-fail", "read_test", Map.of(), "{}", Map.of(), null))
                .input(Map.of())
                .build()).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");
        assertThat(guard.stateOf("", "call-fail")).isEqualTo("ERROR");
    }

    /** 用户中断异常走 INTERRUPTED 终态。 */
    @Test
    void interruptedToolCompletesTheGuardInInterruptedTerminalState() {
        ToolInvocationLedger ledger = mock(ToolInvocationLedger.class);
        AgentToolInvocation record = new AgentToolInvocation();
        record.setId("inv-interrupt");
        when(ledger.start(any(), any())).thenReturn(record);
        ToolCallStateGuard guard = new ToolCallStateGuard();
        AgentScopeToolPipeline pipeline = new AgentScopeToolPipeline(
                ledger, mock(ToolExecutionTemplate.class),
                mock(MediaToolPreflightService.class), new GenerationPreferenceDefaults(),
                mock(AgentWorkflowService.class), guard);
        AgentScopeTool tool = new AgentScopeTool(
                "read_test", "test", AgentScopeTool.schema(Map.of(), List.of()),
                true, true, false,
                (input, param) -> {
                    throw new AgentInterruptException();
                });

        assertThatThrownBy(() -> pipeline.wrap(tool).callAsync(ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock("call-interrupt", "read_test", Map.of(), "{}", Map.of(), null))
                .input(Map.of())
                .build()).block())
                .isInstanceOf(AgentInterruptException.class);
        assertThat(guard.stateOf("", "call-interrupt")).isEqualTo("INTERRUPTED");
    }

    /**
     * Item 2 的核心证明：模型可见 JSON 与「raw → normalize → serialize」完全一致，
     * 即 asMap/callAsync 的额外归一被移除后输出未变。null 仍必须是 EMPTY_TOOL_RESULT 错误信封。
     */
    @Test
    void modelVisibleJsonIsIdenticalForRawAndMapResults() {
        Map<String, Object> cases = new java.util.LinkedHashMap<>();
        cases.put("map-ok", Map.of("ok", true));
        cases.put("map-nested-failure",
                Map.of("ok", true, "result", Map.of("ok", false, "message", "x")));
        cases.put("null", null);
        cases.put("string", "hello");
        cases.put("list", List.of("a", "b"));

        for (Map.Entry<String, Object> item : cases.entrySet()) {
            Object raw = item.getValue();
            String expected = ToolResultContract.serialize(
                    ToolResultContract.normalize("read_test", raw));
            ToolInvocationLedger ledger = mock(ToolInvocationLedger.class);
            AgentToolInvocation record = new AgentToolInvocation();
            record.setId("inv-" + item.getKey());
            when(ledger.start(any(), any())).thenReturn(record);
            AgentScopeToolPipeline pipeline = new AgentScopeToolPipeline(
                    ledger, mock(ToolExecutionTemplate.class),
                    mock(MediaToolPreflightService.class), new GenerationPreferenceDefaults(),
                    mock(AgentWorkflowService.class), new ToolCallStateGuard());
            AgentScopeTool tool = new AgentScopeTool(
                    "read_test", "test", AgentScopeTool.schema(Map.of(), List.of()),
                    true, true, false,
                    (input, param) -> raw);

            var response = pipeline.wrap(tool).callAsync(ToolCallParam.builder()
                    .toolUseBlock(new ToolUseBlock("call-" + item.getKey(), "read_test",
                            Map.of(), "{}", Map.of(), null))
                    .input(Map.of())
                    .build()).block();

            assertThat(text(response)).as("case=%s", item.getKey()).isEqualTo(expected);
        }
    }

    @Test
    void nullToolResultReachesTheModelAsContractError() throws Exception {
        ToolInvocationLedger ledger = mock(ToolInvocationLedger.class);
        AgentToolInvocation record = new AgentToolInvocation();
        record.setId("inv-null");
        when(ledger.start(any(), any())).thenReturn(record);
        ToolExecutionTemplate template = mock(ToolExecutionTemplate.class);
        AgentScopeToolPipeline pipeline = new AgentScopeToolPipeline(
                ledger, template, mock(MediaToolPreflightService.class),
                new GenerationPreferenceDefaults(), mock(AgentWorkflowService.class),
                new ToolCallStateGuard());
        AgentScopeTool tool = new AgentScopeTool(
                "read_test", "test", AgentScopeTool.schema(Map.of(), List.of()),
                true, true, false,
                (input, param) -> null);

        var response = pipeline.wrap(tool).callAsync(ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock("call-null", "read_test", Map.of(), "{}", Map.of(), null))
                .input(Map.of())
                .build()).block();

        Map<String, Object> payload = payload(response);
        assertThat(payload)
                .containsEntry("ok", false)
                .containsEntry("resultStatus", "error");
        assertThat(errorCode(payload)).isEqualTo("EMPTY_TOOL_RESULT");
        verify(template, times(1)).finish(any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void nestedFailureReturnedByToolIsNotDisguisedForTheModel() throws Exception {
        ToolInvocationLedger ledger = mock(ToolInvocationLedger.class);
        AgentToolInvocation record = new AgentToolInvocation();
        record.setId("inv-nested");
        when(ledger.start(any(), any())).thenReturn(record);
        AgentScopeToolPipeline pipeline = new AgentScopeToolPipeline(
                ledger, mock(ToolExecutionTemplate.class),
                mock(MediaToolPreflightService.class), new GenerationPreferenceDefaults(),
                mock(AgentWorkflowService.class), new ToolCallStateGuard());
        AgentScopeTool tool = new AgentScopeTool(
                "read_test", "test", AgentScopeTool.schema(Map.of(), List.of()),
                true, true, false,
                (input, param) -> Map.of("ok", true, "result", Map.of("ok", false, "message", "x")));

        var response = pipeline.wrap(tool).callAsync(ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock("call-nested", "read_test", Map.of(), "{}", Map.of(), null))
                .input(Map.of())
                .build()).block();

        Map<String, Object> payload = payload(response);
        assertThat(payload)
                .containsEntry("ok", false)
                .containsEntry("resultStatus", "error")
                .containsEntry("message", "x");
        assertThat(errorCode(payload)).isEqualTo("TOOL_FAILED");
        // 内层明细原样保留，且没有被二次归一（说明只归一了一次）
        assertThat(payload.get("result")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) payload.get("result");
        assertThat(detail)
                .containsEntry("ok", false)
                .doesNotContainKey("resultStatus");
    }

    private static Map<String, Object> payload(
            io.agentscope.core.message.ToolResultBlock response) throws Exception {
        return new ObjectMapper().readValue(text(response), new TypeReference<Map<String, Object>>() { });
    }

    @SuppressWarnings("unchecked")
    private static String errorCode(Map<String, Object> payload) {
        Object error = payload.get("error");
        assertThat(error).isInstanceOf(Map.class);
        return String.valueOf(((Map<String, Object>) error).get("code"));
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
        return runtimeWithRequest(preferences, "来张图");
    }

    private static RuntimeContext runtimeWithRequest(AgentGenerationPreferences preferences,
                                                     String content) {
        AgentConversation conversation = mock(AgentConversation.class);
        OrchestrationRequest request = new OrchestrationRequest(conversation, content, List.of(), null);
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
