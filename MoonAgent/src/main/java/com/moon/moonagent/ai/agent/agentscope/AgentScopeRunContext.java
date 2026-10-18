package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.handler.AgentRuntimeSupport;
import com.moon.moonagent.ai.agent.handler.AgentTraceAccumulator;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.agent.recovery.ExternalActionLedgerService;
import com.moon.moonagent.ai.agent.turn.AgentTurnEventPublisher;
import com.moon.moonagent.ai.agent.turn.TurnSteerInbox;
import com.moon.moonagent.entity.AgentConversation;

/**
 * Per-call bridge context injected into AgentScope tools.
 *
 * <p>{@code steerInbox} 只在正常用户回合里非空；wakeup/子智能体等路径为 null，
 * 此时 {@link SteerMiddleware} 完全透明。
 *
 * <p>{@code toolProfile} 是本轮/本会话允许的技能与工具组组合；{@code null} 表示不限制。
 * 它由会话/项目/模式推导，不看套餐或用户等级。
 */
public record AgentScopeRunContext(
        AgentConversation conversation,
        OrchestrationRequest request,
        AgentRuntimeSupport support,
        AgentScopeTurnState turnState,
        String checkpointId,
        ExternalActionLedgerService externalActions,
        TurnSteerInbox steerInbox,
        ToolCatalog.Profile toolProfile,
        AgentTurnEventPublisher publisher
) {
    /** 兼容旧构造：不需要自定义事件的调用方传 null publisher。 */
    public AgentScopeRunContext(AgentConversation conversation,
                                OrchestrationRequest request,
                                AgentRuntimeSupport support,
                                AgentScopeTurnState turnState,
                                String checkpointId,
                                ExternalActionLedgerService externalActions,
                                TurnSteerInbox steerInbox,
                                ToolCatalog.Profile toolProfile) {
        this(conversation, request, support, turnState, checkpointId, externalActions,
                steerInbox, toolProfile, null);
    }

    public AgentScopeRunContext(AgentConversation conversation,
                                OrchestrationRequest request,
                                AgentRuntimeSupport support,
                                AgentScopeTurnState turnState) {
        this(conversation, request, support, turnState, null, null, null, null);
    }

    public AgentScopeRunContext(AgentConversation conversation,
                                OrchestrationRequest request,
                                AgentRuntimeSupport support,
                                AgentScopeTurnState turnState,
                                String checkpointId,
                                ExternalActionLedgerService externalActions) {
        this(conversation, request, support, turnState, checkpointId, externalActions, null, null);
    }

    public AgentScopeRunContext(AgentConversation conversation,
                                OrchestrationRequest request,
                                AgentRuntimeSupport support,
                                AgentScopeTurnState turnState,
                                String checkpointId,
                                ExternalActionLedgerService externalActions,
                                TurnSteerInbox steerInbox) {
        this(conversation, request, support, turnState, checkpointId, externalActions,
                steerInbox, null);
    }

    AgentTraceAccumulator trace() {
        return request == null ? null : request.getTrace();
    }
}
