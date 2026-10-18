package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.turn.TurnSteerInbox;
import com.moon.moonagent.ai.agent.turn.TurnSteerMessage;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 把「回合中插入」的用户消息注入到下一个模型步骤。
 *
 * <p>注入点选在 {@code onReasoning}：此时上一批工具结果已经落地，追加一条 user 消息是合法的
 * 消息序列；不会打断正在执行的工具，也不会另起一个 turn。消息同时写进当前请求的
 * {@link ReasoningInput} 与 {@link AgentState}，后续步骤从 state 读取，不会重复注入。
 *
 * <p>没有任何在途回合（{@link AgentScopeRunContext#steerInbox()} 为 null）时完全透明。
 */
final class SteerMiddleware implements MiddlewareBase {

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        AgentScopeRunContext run = ctx == null ? null : ctx.get(AgentScopeRunContext.class);
        TurnSteerInbox inbox = run == null ? null : run.steerInbox();
        if (inbox == null) {
            return next.apply(input);
        }
        List<TurnSteerMessage> pending = inbox.drain();
        if (pending.isEmpty()) {
            return next.apply(input);
        }

        List<Msg> messages = new ArrayList<>(input.messages());
        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
        List<Msg> context = state == null ? null : state.contextMutable();
        // 正常回合中插入：追加在工具结果之后。若这是新回合的第一步、输入末尾已经是
        // 新的 user 消息（上一轮残留被带入），则插在它之前，保持真实时间顺序。
        int messageAt = insertionPoint(messages);
        int stateAt = context == null ? -1 : insertionPoint(context);
        for (TurnSteerMessage message : pending) {
            Msg injected = Msg.builder()
                    .name("user")
                    .role(MsgRole.USER)
                    .textContent(message.content())
                    .metadata(metadata(message))
                    .build();
            messages.add(messageAt++, injected);
            if (context != null) {
                context.add(stateAt++, injected);
            }
        }
        return next.apply(new ReasoningInput(messages, input.tools(), input.options()));
    }

    private static int insertionPoint(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int last = messages.size() - 1;
        return messages.get(last).getRole() == MsgRole.USER ? last : messages.size();
    }

    private static Map<String, Object> metadata(TurnSteerMessage message) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("moonSteer", true);
        metadata.put("messageId", message.messageId());
        return metadata;
    }
}
