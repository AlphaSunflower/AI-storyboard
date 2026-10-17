package com.moon.moonagent.ai.agent.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;

/**
 * 每个模型步的耗时与 token 埋点（**只打日志**，不改请求也不改响应）。
 *
 * <p><b>为什么</b>：这一轮的所有提速讨论都缺一个可对照的数字。实测日志里只有 PRE/POST_REASONING
 * 的时间戳，得人工相减；而 {@code ModelCallEndEvent.getUsage()} 里其实已经有
 * {@code inputTokens/outputTokens/cachedTokens} 和模型自报的耗时（{@code getTime()}，秒）。
 * 这里把它打成一行 INFO，让"改前 / 改后"可以直接 grep 对比。
 *
 * <p>输出示例：
 * <pre>
 * [agent-speed] modelStep 用时=84123ms modelMs=83900 inputTokens=41234 outputTokens=812
 *               cachedTokens=40000 上一步工具=canvas_graph_create_nodes turn=...
 * </pre>
 *
 * <p>它挂在 {@code onModelCall} 上，因此**一次模型调用 = 一行**（含流式）；失败也会留一行，
 * 便于区分"慢"和"挂了"。
 */
@Slf4j
final class ModelStepTimingMiddleware implements MiddlewareBase {

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent,
                                        RuntimeContext ctx,
                                        ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        long startedAt = System.nanoTime();
        String trigger = lastMessageName(input.messages());
        String turn = turnId(ctx);
        return next.apply(input)
                .doOnNext(event -> {
                    if (event instanceof ModelCallEndEvent end) {
                        logStep(startedAt, end, trigger, turn);
                    }
                })
                .doOnError(error -> log.info(
                        "[agent-speed] modelStep 失败 用时={}ms 上一步工具={} turn={} error={}",
                        elapsedMillis(startedAt), trigger, turn,
                        error == null ? "" : error.getMessage()));
    }

    private static void logStep(long startedAt, ModelCallEndEvent end, String trigger, String turn) {
        var usage = end == null ? null : end.getUsage();
        if (usage == null) {
            log.info("[agent-speed] modelStep 用时={}ms（模型未返回 usage）上一步工具={} turn={}",
                    elapsedMillis(startedAt), trigger, turn);
            return;
        }
        long modelMs = Math.round(Math.max(0.0d, usage.getTime()) * 1000.0d);
        log.info("[agent-speed] modelStep 用时={}ms modelMs={} inputTokens={} outputTokens={} "
                        + "cachedTokens={} 上一步工具={} turn={}",
                elapsedMillis(startedAt), modelMs, usage.getInputTokens(), usage.getOutputTokens(),
                usage.getCachedTokens(), trigger, turn);
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private static String lastMessageName(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) return "";
        Msg last = messages.get(messages.size() - 1);
        return last == null || last.getName() == null ? "" : last.getName();
    }

    private static String turnId(RuntimeContext ctx) {
        AgentScopeRunContext context = ctx == null ? null : ctx.get(AgentScopeRunContext.class);
        if (context == null || context.request() == null) return "";
        String turnId = context.request().getTurnId();
        return turnId == null ? "" : turnId;
    }
}
