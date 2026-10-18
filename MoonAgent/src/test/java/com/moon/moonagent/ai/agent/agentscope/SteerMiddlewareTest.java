package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.turn.TurnSteerInbox;
import com.moon.moonagent.ai.agent.turn.TurnSteerMessage;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SteerMiddlewareTest {

    @Test
    void carriedSteerIsInsertedBeforeTheNewTurnsUserMessage() {
        TurnSteerInbox inbox = new TurnSteerInbox();
        inbox.offer(new TurnSteerMessage("old", "上一轮的补充", OffsetDateTime.now()));

        Msg newUser = user("新一轮的问题");
        AgentState state = AgentState.builder().build();
        state.contextMutable().add(newUser);

        RuntimeContext ctx = runtime(state, inbox);
        List<ReasoningInput> captured = new ArrayList<>();
        new SteerMiddleware()
                .onReasoning(mock(Agent.class), ctx,
                        new ReasoningInput(List.of(newUser), List.of(), null),
                        input -> {
                            captured.add(input);
                            return Flux.empty();
                        })
                .blockLast();

        assertThat(captured).hasSize(1);
        assertThat(captured.getFirst().messages())
                .extracting(Msg::getTextContent)
                .containsExactly("上一轮的补充", "新一轮的问题");
        assertThat(state.contextMutable())
                .extracting(Msg::getTextContent)
                .containsExactly("上一轮的补充", "新一轮的问题");
    }

    @Test
    void midTurnSteerIsAppendedAfterToolResults() {
        TurnSteerInbox inbox = new TurnSteerInbox();
        inbox.offer(new TurnSteerMessage("mid", "改成暖色", OffsetDateTime.now()));

        Msg toolResult = Msg.builder()
                .name("run_python_script")
                .role(MsgRole.TOOL)
                .textContent("done")
                .build();
        AgentState state = AgentState.builder().build();
        state.contextMutable().add(toolResult);

        RuntimeContext ctx = runtime(state, inbox);
        List<ReasoningInput> captured = new ArrayList<>();
        new SteerMiddleware()
                .onReasoning(mock(Agent.class), ctx,
                        new ReasoningInput(List.of(toolResult), List.of(), null),
                        input -> {
                            captured.add(input);
                            return Flux.empty();
                        })
                .blockLast();

        assertThat(captured.getFirst().messages())
                .extracting(Msg::getTextContent)
                .containsExactly("done", "改成暖色");
    }

    private static RuntimeContext runtime(AgentState state, TurnSteerInbox inbox) {
        AgentScopeRunContext run = new AgentScopeRunContext(
                null, null, null, new AgentScopeTurnState(), null, null, inbox, null);
        return RuntimeContext.builder()
                .agentState(state)
                .put(AgentScopeRunContext.class, run)
                .build();
    }

    private static Msg user(String text) {
        return Msg.builder().name("user").role(MsgRole.USER).textContent(text).build();
    }
}
