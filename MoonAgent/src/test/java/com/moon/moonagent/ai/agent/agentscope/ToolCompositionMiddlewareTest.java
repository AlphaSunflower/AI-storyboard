package com.moon.moonagent.ai.agent.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ToolCompositionMiddlewareTest {

    @Test
    void canvasProfileRemovesStoryboardGroupButKeepsActivatedImageGroup() {
        AgentState state = AgentState.builder().build();
        state.getToolContext().addActivatedGroups(
                List.of(ToolCatalog.STORYBOARD_TOOLS, ToolCatalog.IMAGE_TOOLS));

        RuntimeContext ctx = runtime(state, ToolCatalog.profileFor(true));
        new ToolCompositionMiddleware()
                .onReasoning(mock(Agent.class), ctx, input(), next -> Flux.empty())
                .blockLast();

        assertThat(state.getToolContext().getActivatedGroups())
                .containsExactly(ToolCatalog.IMAGE_TOOLS);
    }

    @Test
    void normalProfileKeepsEveryActivatedGroup() {
        AgentState state = AgentState.builder().build();
        state.getToolContext().addActivatedGroups(
                List.of(ToolCatalog.STORYBOARD_TOOLS, ToolCatalog.VIDEO_TOOLS));

        RuntimeContext ctx = runtime(state, ToolCatalog.profileFor(false));
        new ToolCompositionMiddleware()
                .onReasoning(mock(Agent.class), ctx, input(), next -> Flux.empty())
                .blockLast();

        assertThat(state.getToolContext().getActivatedGroups())
                .containsExactly(ToolCatalog.STORYBOARD_TOOLS, ToolCatalog.VIDEO_TOOLS);
    }

    private static RuntimeContext runtime(AgentState state, ToolCatalog.Profile profile) {
        AgentScopeRunContext run = new AgentScopeRunContext(
                null, null, null, new AgentScopeTurnState(), null, null, null, profile);
        return RuntimeContext.builder()
                .agentState(state)
                .put(AgentScopeRunContext.class, run)
                .build();
    }

    private static ReasoningInput input() {
        return new ReasoningInput(List.of(), List.of(), null);
    }
}
