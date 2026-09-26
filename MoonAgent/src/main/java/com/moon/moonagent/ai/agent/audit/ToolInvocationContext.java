package com.moon.moonagent.ai.agent.audit;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ToolInvocationContext {
    private String invocationKey;
    private String traceId;
    private String loopRunId;
    private String loopStepId;
    private String taskId;
    private String conversationId;
    private String userId;
    private String projectId;
    private String toolCallId;
    private String toolName;
    private String toolKind;
    private String source;
    private String actionType;
    private String skillVersion;
    private String model;
    private String engineVersion;
}
