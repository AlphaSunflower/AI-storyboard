package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.handler.AgentTraceAccumulator;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

final class AgentTraceBridgeMiddleware implements MiddlewareBase {

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext context,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        AgentTraceAccumulator trace = trace(context);
        if (trace == null) return next.apply(input);
        return Flux.defer(() -> {
            String id = trace.start("model_reasoning", "agentscope", "模型推理", "");
            AtomicBoolean ended = new AtomicBoolean(false);
            return next.apply(input)
                    .doOnComplete(() -> {
                        if (ended.compareAndSet(false, true)) {
                            trace.end(id, "node_completed", "推理完成");
                        }
                    })
                    .doOnError(error -> {
                        if (ended.compareAndSet(false, true)) {
                            trace.end(id, "node_failed", error.getMessage());
                        }
                    });
        });
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext context,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        AgentTraceAccumulator trace = trace(context);
        if (trace == null || input.toolCalls() == null || input.toolCalls().isEmpty()) {
            return next.apply(input);
        }
        Map<String, String> traceIds = new ConcurrentHashMap<>();
        for (var call : input.toolCalls()) {
            traceIds.put(call.getId(), trace.start(
                    "tool_call", "agentscope",
                    AgentScopeToolLabels.displayName(call.getName()),
                    AgentScopeToolLabels.displayName(call.getName())));
        }
        return next.apply(input)
                .doOnNext(event -> {
                    if (event instanceof ToolResultEndEvent result) {
                        String id = traceIds.remove(result.getToolCallId());
                        if (id != null) {
                            trace.end(id,
                                    result.getState() == null ? "node_completed"
                                            : "node_" + result.getState().name().toLowerCase(),
                                    AgentScopeToolLabels.displayName(result.getToolCallName()));
                        }
                    }
                });
    }

    private static AgentTraceAccumulator trace(RuntimeContext context) {
        AgentScopeRunContext runContext =
                context == null ? null : context.get(AgentScopeRunContext.class);
        return runContext == null ? null : runContext.trace();
    }
}
