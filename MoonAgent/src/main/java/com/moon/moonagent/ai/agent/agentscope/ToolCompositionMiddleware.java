package com.moon.moonagent.ai.agent.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 每会话工具组合的执行侧守卫。
 *
 * <p>{@link ToolCatalog.Profile} 给出本轮允许的技能与工具组；技能白名单由
 * {@code SkillFilter} 控制，工具组白名单在这里落地——把会话状态里已经激活、
 * 但本组合不允许的组移除。只移除、不主动激活，因此不会清掉用户在同一模式内
 * 已经加载的技能组。
 *
 * <p>状态来自 {@code (userId, sessionId)} 的 {@link AgentState}，天然按会话隔离；
 * 不同会话可以有不同的组合，互不影响。
 */
final class ToolCompositionMiddleware implements MiddlewareBase {

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        AgentScopeRunContext run = ctx == null ? null : ctx.get(AgentScopeRunContext.class);
        ToolCatalog.Profile profile = run == null ? null : run.toolProfile();
        if (profile == null) {
            return next.apply(input);
        }
        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
        if (state == null || state.getToolContext() == null) {
            return next.apply(input);
        }
        List<String> active = state.getToolContext().getActivatedGroups();
        if (active.isEmpty()) {
            return next.apply(input);
        }
        List<String> disallowed = new ArrayList<>();
        for (String group : active) {
            if (!profile.groups().contains(group)) {
                disallowed.add(group);
            }
        }
        if (!disallowed.isEmpty()) {
            state.getToolContext().removeActivatedGroups(disallowed);
        }
        return next.apply(input);
    }
}
