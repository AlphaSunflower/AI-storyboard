package com.moon.moonagent.ai.agent.audit;

import com.moon.moonagent.entity.AgentToolInvocation;

import java.time.OffsetDateTime;

public record ToolInvocationScope(
        AgentToolInvocation invocation,
        ToolInvocationContext context,
        OffsetDateTime startedAt
) {
}
