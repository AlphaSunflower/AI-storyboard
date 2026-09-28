package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.handler.AgentRuntimeSupport;
import com.moon.moonagent.ai.agent.handler.AgentTraceAccumulator;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.agent.recovery.ExternalActionLedgerService;
import com.moon.moonagent.entity.AgentConversation;

/**
 * Per-call bridge context injected into AgentScope tools.
 */
public record AgentScopeRunContext(
        AgentConversation conversation,
        OrchestrationRequest request,
        AgentRuntimeSupport support,
        AgentScopeTurnState turnState,
        String checkpointId,
        ExternalActionLedgerService externalActions
) {
    public AgentScopeRunContext(AgentConversation conversation,
                                OrchestrationRequest request,
                                AgentRuntimeSupport support,
                                AgentScopeTurnState turnState) {
        this(conversation, request, support, turnState, null, null);
    }

    AgentTraceAccumulator trace() {
        return request == null ? null : request.getTrace();
    }
}
