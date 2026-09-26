package com.moon.moonagent.entity;

import lombok.Data;

@Data
public class AgentCheckpointRuntime {

    private String runtimeSessionId;
    private String runtimeReplyId;
    private String runtimeToolCallId;
    private String runtimeToolName;
    private String runtimeToolCallsJson;
    private String resumePayload;

    public void applyTo(AgentCheckpoint checkpoint) {
        if (checkpoint == null) return;
        checkpoint.setRuntimeSessionId(runtimeSessionId);
        checkpoint.setRuntimeReplyId(runtimeReplyId);
        checkpoint.setRuntimeToolCallId(runtimeToolCallId);
        checkpoint.setRuntimeToolName(runtimeToolName);
        checkpoint.setRuntimeToolCallsJson(runtimeToolCallsJson);
        checkpoint.setResumePayload(resumePayload);
    }
}
