package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.sandbox.MoonSandboxClient;
import com.moon.moonagent.entity.AgentConversation;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextOverflowMiddlewareTest {

    @Test
    void oversizedToolResultIsSpilledAndReplacedByALocator() {
        String huge = "x".repeat(120_000);
        ToolResultBlock block = new ToolResultBlock(
                "call-1", "run_python_script",
                List.of(TextBlock.builder().text(huge).build()),
                Map.of(), ToolResultState.SUCCESS);
        Msg message = Msg.builder()
                .id("msg-1")
                .name("run_python_script")
                .role(MsgRole.TOOL)
                .content(block)
                .build();
        AgentState state = AgentState.builder().build();
        state.contextMutable().add(message);

        AgentConversation conversation = new AgentConversation();
        conversation.setId("conv-1");
        AgentScopeRunContext run = new AgentScopeRunContext(
                conversation, null, null, new AgentScopeTurnState(), null, null, null, null);
        RuntimeContext ctx = RuntimeContext.builder()
                .agentState(state)
                .put(AgentScopeRunContext.class, run)
                .build();

        Model model = mock(Model.class);
        when(model.getContextWindowSize()).thenReturn(1000);
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.getModel()).thenReturn(model);

        MoonSandboxClient sandbox = mock(MoonSandboxClient.class);
        List<ReasoningInput> captured = new ArrayList<>();
        new ContextOverflowMiddleware(sandbox)
                .onReasoning(agent, ctx,
                        new ReasoningInput(List.of(message), List.of(), null),
                        input -> {
                            captured.add(input);
                            return Flux.empty();
                        })
                .blockLast();

        verify(sandbox).write(anyString(), anyString(), anyString(), anyString());
        ContentBlock replaced = captured.getFirst().messages().getFirst().getContent().getFirst();
        assertThat(replaced).isInstanceOf(ToolResultBlock.class);
        ToolResultBlock result = (ToolResultBlock) replaced;
        assertThat(result.getOutput()).singleElement()
                .isInstanceOf(TextBlock.class)
                .satisfies(item -> assertThat(((TextBlock) item).getText())
                        .contains("work/overflow/", "read_file"));
        assertThat(state.contextMutable().getFirst().getContent().getFirst())
                .isInstanceOf(ToolResultBlock.class);
    }

    @Test
    void normalSizedResultsAreUntouched() {
        ToolResultBlock block = new ToolResultBlock(
                "call-1", "read_document",
                List.of(TextBlock.builder().text("small").build()),
                Map.of(), ToolResultState.SUCCESS);
        Msg message = Msg.builder().id("msg-1").role(MsgRole.TOOL).content(block).build();
        AgentState state = AgentState.builder().build();
        state.contextMutable().add(message);

        AgentConversation conversation = new AgentConversation();
        conversation.setId("conv-1");
        RuntimeContext ctx = RuntimeContext.builder()
                .agentState(state)
                .put(AgentScopeRunContext.class, new AgentScopeRunContext(
                        conversation, null, null, new AgentScopeTurnState(), null, null, null, null))
                .build();
        Model model = mock(Model.class);
        when(model.getContextWindowSize()).thenReturn(100_000);
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.getModel()).thenReturn(model);
        MoonSandboxClient sandbox = mock(MoonSandboxClient.class);

        List<ReasoningInput> captured = new ArrayList<>();
        new ContextOverflowMiddleware(sandbox)
                .onReasoning(agent, ctx,
                        new ReasoningInput(List.of(message), List.of(), null),
                        input -> {
                            captured.add(input);
                            return Flux.empty();
                        })
                .blockLast();

        assertThat(captured.getFirst().messages()).containsExactly(message);
    }
}
