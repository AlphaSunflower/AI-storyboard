package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.audit.ToolAuditProperties;
import com.moon.moonagent.ai.agent.audit.ToolAuditSummarizer;
import com.moon.moonagent.ai.agent.audit.ToolInvocationContext;
import com.moon.moonagent.ai.agent.audit.ToolInvocationLedger;
import com.moon.moonagent.ai.agent.audit.ToolInvocationResult;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.entity.AgentToolInvocation;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FrameworkToolAuditMiddlewareTest {

    private final ToolInvocationLedger ledger = mock(ToolInvocationLedger.class);
    private final ToolAuditSummarizer summarizer = mock(ToolAuditSummarizer.class);
    private ToolAuditProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ToolAuditProperties();
        properties.setEnabled(true);
        when(ledger.start(any(), any())).thenReturn(new AgentToolInvocation());
        when(summarizer.hash(any())).thenReturn("hash");
    }

    @Test
    void auditsFrameworkToolAndFinishesWithSuccessState() {
        middleware().onActing(null, runtimeContext(), actingInput("write_file"),
                        ignored -> Flux.fromIterable(List.of(
                                new ToolResultTextDeltaEvent(
                                        "reply-1", "call-1", "write_file", "Written to output/a.md"),
                                new ToolResultEndEvent(
                                        "reply-1", "call-1", "write_file", ToolResultState.SUCCESS))))
                .blockLast();

        ArgumentCaptor<ToolInvocationContext> context =
                ArgumentCaptor.forClass(ToolInvocationContext.class);
        verify(ledger).start(context.capture(), any());
        assertThat(context.getValue().getToolName()).isEqualTo("write_file");
        assertThat(context.getValue().getConversationId()).isEqualTo("c1");
        assertThat(context.getValue().getUserId()).isEqualTo("u1");
        assertThat(context.getValue().getProjectId()).isEqualTo("p1");
        assertThat(context.getValue().getSource()).isEqualTo("AGENT_SCOPE");

        ArgumentCaptor<ToolInvocationResult> result =
                ArgumentCaptor.forClass(ToolInvocationResult.class);
        verify(ledger).finish(any(), result.capture());
        assertThat(result.getValue().getStatus()).isEqualTo("COMPLETED");
        assertThat(result.getValue().getOutputSummary()).contains("Written to output/a.md");
    }

    @Test
    void mapsErrorStateToFailed() {
        middleware().onActing(null, runtimeContext(), actingInput("execute"),
                        ignored -> Flux.fromIterable(List.of(
                                new ToolResultEndEvent(
                                        "reply-1", "call-1", "execute", ToolResultState.ERROR))))
                .blockLast();

        ArgumentCaptor<ToolInvocationResult> result =
                ArgumentCaptor.forClass(ToolInvocationResult.class);
        verify(ledger).finish(any(), result.capture());
        assertThat(result.getValue().getStatus()).isEqualTo("FAILED");
    }

    @Test
    void closesOpenRecordWhenStreamFails() {
        Flux<AgentEvent> failing = Flux.concat(
                Flux.just(new ToolResultTextDeltaEvent(
                        "reply-1", "call-1", "execute", "partial")),
                Flux.error(new IllegalStateException("boom")));

        try {
            middleware().onActing(null, runtimeContext(), actingInput("execute"),
                    ignored -> failing).blockLast();
        } catch (RuntimeException ignored) {
            // the middleware must not swallow the downstream error
        }

        ArgumentCaptor<ToolInvocationResult> result =
                ArgumentCaptor.forClass(ToolInvocationResult.class);
        verify(ledger).finish(any(), result.capture());
        assertThat(result.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(result.getValue().getErrorMessage()).contains("boom");
    }

    @Test
    void ignoresToolsOutsideTheFrameworkWhitelist() {
        middleware().onActing(null, runtimeContext(), actingInput("generate_image"),
                        ignored -> Flux.empty())
                .blockLast();

        verify(ledger, never()).start(any(), any());
        verify(ledger, never()).finish(any(), any());
    }

    @Test
    void isNoOpWhenAuditDisabled() {
        properties.setEnabled(false);

        middleware().onActing(null, runtimeContext(), actingInput("write_file"),
                        ignored -> Flux.empty())
                .blockLast();

        verify(ledger, never()).start(any(), any());
        verify(ledger, never()).finish(any(), any());
    }

    private FrameworkToolAuditMiddleware middleware() {
        return new FrameworkToolAuditMiddleware(ledger, summarizer, properties);
    }

    private static ActingInput actingInput(String toolName) {
        return new ActingInput(List.of(new ToolUseBlock(
                "call-1", toolName,
                Map.of("path", "output/a.md"),
                "{\"path\":\"output/a.md\"}",
                Map.of(),
                null)));
    }

    private static RuntimeContext runtimeContext() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setUserId("u1");
        conversation.setProjectId("p1");
        return RuntimeContext.builder()
                .userId("u1")
                .sessionId("c1")
                .put(AgentScopeRunContext.class,
                        new AgentScopeRunContext(
                                conversation, null, null, new AgentScopeTurnState()))
                .build();
    }
}
