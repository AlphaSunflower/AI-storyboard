package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

final class MediaProgressMiddleware implements MiddlewareBase {

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext context,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        AgentScopeRunContext runContext =
                context == null ? null : context.get(AgentScopeRunContext.class);
        if (runContext == null || runContext.request() == null
                || runContext.request().getEmitter() == null || input.toolCalls() == null) {
            return next.apply(input);
        }
        for (var call : input.toolCalls()) {
            String title = switch (call.getName()) {
                case AgentScopeImageTools.GENERATE_IMAGE -> "正在生成图片";
                case AgentScopeImageTools.GENERATE_IMAGE_BATCH -> "正在创建批量生图任务";
                case AgentScopeVideoTools.GENERATE_VIDEO -> "正在创建视频任务";
                default -> "";
            };
            if (title.isBlank()) continue;
            send(runContext, call.getId(), title, "node_started");
        }
        return next.apply(input);
    }

    private static void send(AgentScopeRunContext context, String stage, String title,
                             String status) {
        try {
            SseEmitter emitter = context.request().getEmitter();
            if (emitter == null) return;
            emitter.send(SseEmitter.event().name("workflow")
                    .data(AgentOrchestratorSupport.sseJson(Map.of(
                            "stage", stage,
                            "title", title,
                            "status", status))));
        } catch (Exception ignored) {
            // Client disconnected.
        }
    }
}
