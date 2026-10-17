package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.CanvasSessionContext;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.GenerateOptions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 画布结构性步骤压思考：只有"续跑画布命令"的那一步压，首轮创意步骤与其它技能一律不动。
 *
 * <p>这些断言的边界比实现本身更重要——一旦压过头，模型在需要创意的步骤（拆镜头、写提示词）
 * 会变粗糙，而那种退化不会报错、只会变差，所以必须由测试守住触发条件。
 */
class CanvasStructuralThinkingMiddlewareTest {

    private final CanvasStructuralThinkingMiddleware middleware =
            new CanvasStructuralThinkingMiddleware();

    @Test
    void compressesThinkingWhenContinuingAfterACanvasTool() {
        GenerateOptions original = GenerateOptions.builder()
                .modelName("mimo-v2.6-pro")
                .temperature(0.7d)
                .build();

        GenerateOptions captured = capture(canvasRuntimeContext(), toolMessage("canvas_graph_create_nodes"), original);

        assertThat(captured.getAdditionalBodyParams())
                .containsEntry(CanvasStructuralThinkingMiddleware.THINKING_LEVEL_KEY,
                        CanvasStructuralThinkingMiddleware.THINKING_LEVEL_MINIMAL);
        // 只追加 thinking_level，其余字段必须原样保留（merge 不能把模型名/温度弄丢）
        assertThat(captured.getModelName()).isEqualTo("mimo-v2.6-pro");
        assertThat(captured.getTemperature()).isEqualTo(0.7d);
    }

    @Test
    void keepsFullThinkingOnTheFirstPlanningStepOfTheSameTurn() {
        // 同一轮画布任务：最后一条是用户消息 = 首轮创意定调，绝不能压
        GenerateOptions original = GenerateOptions.builder().modelName("mimo-v2.6-pro").build();

        GenerateOptions captured = capture(canvasRuntimeContext(), userMessage("帮我搭一条六镜头的分镜链路"), original);

        assertThat(captured).isSameAs(original);
    }

    @Test
    void ignoresToolResultsFromNonCanvasTools() {
        // 画布轮里也可能跑生图/文件工具：那种步骤不一定是结构性续跑，不压
        GenerateOptions original = GenerateOptions.builder().modelName("mimo-v2.6-pro").build();

        assertThat(capture(canvasRuntimeContext(), toolMessage("generate_image"), original)).isSameAs(original);
    }

    @Test
    void ignoresCanvasToolResultsOutsideCanvasEditMode() {
        GenerateOptions original = GenerateOptions.builder().modelName("mimo-v2.6-pro").build();

        // 非画布轮（没有画布上下文）
        assertThat(capture(runtimeContextWithoutCanvas(), toolMessage("canvas_graph_create_nodes"), original))
                .isSameAs(original);
        // 有画布上下文但不是编辑模式
        assertThat(capture(runtimeContextWithCanvas("natural"), toolMessage("canvas_graph_create_nodes"), original))
                .isSameAs(original);
    }

    @Test
    void failsOpenWhenRunContextIsMissingOrOptionsAreNull() {
        GenerateOptions original = GenerateOptions.builder().modelName("mimo-v2.6-pro").build();

        // 完全没有注入 AgentScopeRunContext（单测/非 AgentScope 路径）
        RuntimeContext bare = RuntimeContext.builder().userId("u").sessionId("s").build();
        assertThat(capture(bare, toolMessage("canvas_graph_create_nodes"), original)).isSameAs(original);
        // 选项为空
        assertThat(capture(canvasRuntimeContext(), toolMessage("canvas_graph_create_nodes"), null)).isNull();
    }

    @Test
    void doesNotWrapTwiceWhenAlreadyMinimal() {
        GenerateOptions alreadyMinimal = GenerateOptions.builder()
                .modelName("mimo-v2.6-pro")
                .additionalBodyParam(CanvasStructuralThinkingMiddleware.THINKING_LEVEL_KEY,
                        CanvasStructuralThinkingMiddleware.THINKING_LEVEL_MINIMAL)
                .build();

        assertThat(capture(canvasRuntimeContext(), toolMessage("canvas_graph_apply_plan"), alreadyMinimal))
                .isSameAs(alreadyMinimal);
    }

    // ── 测试脚手架 ────────────────────────────────────────────────────

    private GenerateOptions capture(RuntimeContext ctx, Msg lastMessage, GenerateOptions options) {
        List<Msg> messages = new ArrayList<>();
        messages.add(userMessage("历史消息"));
        messages.add(lastMessage);
        ModelCallInput input = new ModelCallInput(messages, List.of(), options, null);
        AtomicReference<ModelCallInput> seen = new AtomicReference<>();

        Flux<AgentEvent> result = middleware.onModelCall(null, ctx, input, next -> {
            seen.set(next);
            return Flux.empty();
        });
        result.blockLast();

        ModelCallInput forwarded = seen.get();
        assertThat(forwarded).as("中间件必须把输入交给 next").isNotNull();
        return forwarded.options();
    }

    private static RuntimeContext canvasRuntimeContext() {
        return runtimeContextWithCanvas("canvas-edit");
    }

    private static RuntimeContext runtimeContextWithCanvas(String mode) {
        OrchestrationRequest request = mock(OrchestrationRequest.class);
        when(request.getTurnId()).thenReturn("turn-1");
        when(request.getCanvasContext())
                .thenReturn(new CanvasSessionContext(mode, "project-1", "canvas-1", List.of()));
        return RuntimeContext.builder()
                .userId("user-1")
                .sessionId("session-1")
                .put(AgentScopeRunContext.class,
                        new AgentScopeRunContext(null, request, null, null))
                .build();
    }

    private static RuntimeContext runtimeContextWithoutCanvas() {
        OrchestrationRequest request = mock(OrchestrationRequest.class);
        when(request.getTurnId()).thenReturn("turn-1");
        when(request.getCanvasContext()).thenReturn(null);
        return RuntimeContext.builder()
                .userId("user-1")
                .sessionId("session-1")
                .put(AgentScopeRunContext.class,
                        new AgentScopeRunContext(null, request, null, null))
                .build();
    }

    private static Msg toolMessage(String toolName) {
        return Msg.builder()
                .name(toolName)
                .role(MsgRole.TOOL)
                .content(TextBlock.builder().text("{\"ok\":true}").build())
                .build();
    }

    private static Msg userMessage(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }
}
