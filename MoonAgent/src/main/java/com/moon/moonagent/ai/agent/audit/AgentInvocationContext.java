package com.moon.moonagent.ai.agent.audit;

public record AgentInvocationContext(
        String userId,
        String conversationId,
        String projectId,
        String traceId,
        String loopRunId,
        String loopStepId,
        String taskId,
        String checkpointId,
        String planId,
        String nodeId,
        String skillVersion,
        String model,
        String engineVersion
) {

    public static final AgentInvocationContext EMPTY = new AgentInvocationContext(
            "", "", "", "", "", "", "", "", "", "", "", "", "");

    public AgentInvocationContext {
        userId = text(userId);
        conversationId = text(conversationId);
        projectId = text(projectId);
        traceId = text(traceId);
        loopRunId = text(loopRunId);
        loopStepId = text(loopStepId);
        taskId = text(taskId);
        checkpointId = text(checkpointId);
        planId = text(planId);
        nodeId = text(nodeId);
        skillVersion = text(skillVersion);
        model = text(model);
        engineVersion = text(engineVersion);
    }

    public AgentInvocationContext merge(AgentInvocationContext override) {
        if (override == null) return this;
        return new AgentInvocationContext(
                prefer(override.userId, userId),
                prefer(override.conversationId, conversationId),
                prefer(override.projectId, projectId),
                prefer(override.traceId, traceId),
                prefer(override.loopRunId, loopRunId),
                prefer(override.loopStepId, loopStepId),
                prefer(override.taskId, taskId),
                prefer(override.checkpointId, checkpointId),
                prefer(override.planId, planId),
                prefer(override.nodeId, nodeId),
                prefer(override.skillVersion, skillVersion),
                prefer(override.model, model),
                prefer(override.engineVersion, engineVersion));
    }

    private static String prefer(String first, String fallback) {
        return first == null || first.isBlank() ? text(fallback) : first;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
