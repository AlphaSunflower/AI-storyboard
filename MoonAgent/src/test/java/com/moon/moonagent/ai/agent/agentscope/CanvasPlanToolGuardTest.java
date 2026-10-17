package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.CanvasSessionContext;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.entity.AgentConversation;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CanvasPlanToolGuardTest {

    @Test
    void rejectsInCanvasEditTurnWithoutCallingDelegate() {
        CountingTool delegate = new CountingTool("todo_write");
        CanvasPlanToolGuard guard = new CanvasPlanToolGuard(delegate, mock(AgentCanvasRunService.class));

        ToolResultBlock block = guard.callAsync(canvasEditParam()).block();

        assertThat(delegate.calls()).isZero();
        assertThat(text(block))
                .contains("\"code\":\"PLAN_TOOLS_DISABLED_IN_CANVAS_MODE\"")
                .contains("canvas_graph_apply_plan");
    }

    @Test
    void rejectsWhenTurnAlreadyAdoptedACanvasRun() {
        CountingTool delegate = new CountingTool("plan_enter");
        AgentCanvasRunService runs = mock(AgentCanvasRunService.class);
        when(runs.activeByTurn("turn-1")).thenReturn(new AgentCanvasRunService.CanvasRunView(
                "run-1", "conversation-1", "project-1", "turn-1",
                "canvas-1", "画布", "running", 0,
                0, 0, 0, List.of(), null, OffsetDateTime.now(), null));
        CanvasPlanToolGuard guard = new CanvasPlanToolGuard(delegate, runs);

        ToolResultBlock block = guard.callAsync(param(request(null), new AgentScopeTurnState())).block();

        assertThat(delegate.calls()).isZero();
        assertThat(text(block)).contains("PLAN_TOOLS_DISABLED_IN_CANVAS_MODE");
    }

    @Test
    void passesThroughOutsideCanvasTurn() {
        CountingTool delegate = new CountingTool("plan_write");
        CanvasPlanToolGuard guard = new CanvasPlanToolGuard(delegate, mock(AgentCanvasRunService.class));

        ToolResultBlock block = guard.callAsync(param(request(null), new AgentScopeTurnState())).block();

        assertThat(delegate.calls()).isEqualTo(1);
        assertThat(text(block)).isEqualTo("delegate-output");
    }

    @Test
    void passesThroughWhenRunContextIsMissing() {
        CountingTool delegate = new CountingTool("todo_write");
        CanvasPlanToolGuard guard = new CanvasPlanToolGuard(delegate, mock(AgentCanvasRunService.class));

        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder()
                        .id("call-1").name("todo_write").input(Map.of()).build())
                .build();

        assertThat(text(guard.callAsync(param).block())).isEqualTo("delegate-output");
        assertThat(delegate.calls()).isEqualTo(1);
    }

    @Test
    void failsOpenWhenCanvasDetectionThrows() {
        CountingTool delegate = new CountingTool("plan_enter");
        AgentCanvasRunService runs = mock(AgentCanvasRunService.class);
        when(runs.activeByTurn("turn-1")).thenThrow(new IllegalStateException("db down"));
        CanvasPlanToolGuard guard = new CanvasPlanToolGuard(delegate, runs);

        // 守卫自身判断出错必须 fail-open，不能中断 turn
        assertThat(text(guard.callAsync(param(request(null), new AgentScopeTurnState())).block()))
                .isEqualTo("delegate-output");
        assertThat(delegate.calls()).isEqualTo(1);
    }

    @Test
    void propagatesDelegateFailure() {
        ToolBase failing = new ToolBase(ToolBase.builder()
                .name("plan_enter")
                .description("plan_enter")
                .inputSchema(Map.of("type", "object", "properties", Map.of()))) {
            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.error(new IllegalStateException("delegate failed"));
            }
        };
        CanvasPlanToolGuard guard = new CanvasPlanToolGuard(failing, mock(AgentCanvasRunService.class));

        assertThatThrownBy(() -> guard.callAsync(param(request(null), new AgentScopeTurnState())).block())
                .hasMessageContaining("delegate failed");
    }

    @Test
    void copiesDelegateMetadataAndKeepsName() {
        CountingTool delegate = new CountingTool("plan_exit");
        CanvasPlanToolGuard guard = new CanvasPlanToolGuard(delegate, mock(AgentCanvasRunService.class));

        assertThat(guard.getName()).isEqualTo("plan_exit");
        assertThat(guard.getDescription()).isEqualTo("plan_exit");
        assertThat(guard.getParameters()).containsKey("properties");
    }

    @Test
    void guardedNamesCoverPlanAndTodoTools() {
        assertThat(CanvasPlanToolGuard.GUARDED_TOOL_NAMES)
                .containsExactly("plan_enter", "plan_write", "plan_exit", "todo_write");
    }

    private static ToolCallParam canvasEditParam() {
        OrchestrationRequest request = new OrchestrationRequest(conversation(), "", List.of(), null);
        request.setTurnId("turn-1");
        request.setCanvasContext(new CanvasSessionContext(
                CanvasSessionContext.EDIT_MODE, "project-1", "canvas-1", List.of()));
        return param(request, new AgentScopeTurnState());
    }

    private static ToolCallParam param(OrchestrationRequest request, AgentScopeTurnState turnState) {
        RuntimeContext runtime = RuntimeContext.builder()
                .userId("user-1")
                .sessionId("conversation-1")
                .put(AgentScopeRunContext.class,
                        new AgentScopeRunContext(conversation(), request, null, turnState))
                .build();
        return ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder()
                        .id("call-1")
                        .name("plan_enter")
                        .input(Map.of())
                        .build())
                .runtimeContext(runtime)
                .build();
    }

    private static OrchestrationRequest request(CanvasSessionContext canvasContext) {
        OrchestrationRequest request = new OrchestrationRequest(conversation(), "", List.of(), null);
        request.setTurnId("turn-1");
        request.setCanvasContext(canvasContext);
        return request;
    }

    private static String text(ToolResultBlock block) {
        return ((TextBlock) block.getOutput().get(0)).getText();
    }

    private static AgentConversation conversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setProjectId("project-1");
        return conversation;
    }

    /** 记录调用次数并返回固定文本的 delegate。 */
    private static final class CountingTool extends ToolBase {

        private final AtomicInteger calls = new AtomicInteger();

        private CountingTool(String name) {
            super(ToolBase.builder()
                    .name(name)
                    .description(name)
                    .inputSchema(Map.of("type", "object", "properties", Map.of())));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            calls.incrementAndGet();
            return Mono.just(ToolResultBlock.of(
                    param.getToolUseBlock().getId(),
                    getName(),
                    TextBlock.builder().text("delegate-output").build()));
        }

        private int calls() {
            return calls.get();
        }
    }
}
