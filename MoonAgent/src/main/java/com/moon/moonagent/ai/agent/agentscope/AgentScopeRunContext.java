package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.handler.AgentOrchestratorSupport;
import com.moon.moonagent.ai.agent.handler.AgentTraceAccumulator;
import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.entity.AgentConversation;

/**
 * Per-call bridge context injected into AgentScope tools.
 */
public record AgentScopeRunContext(
        AgentConversation conversation,
        OrchestrationRequest request,
        AgentOrchestratorSupport support,
        AgentScopeTurnState turnState
) {
    AgentTraceAccumulator trace() {
        return request == null ? null : request.getTrace();
    }
}
