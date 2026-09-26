package com.moon.moonagent.ai.agent.task;

public record AgentWakeupMessage(
        String taskId,
        String conversationId,
        String messageId
) {
}
