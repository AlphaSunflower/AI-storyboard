package com.moon.moonagent.ai.agent.audit;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ToolInvocationResult {
    private String status;
    private String outputHash;
    private String outputSummary;
    private String errorCode;
    private String errorMessage;
    private String externalActionKey;
    private String providerTaskId;
    private List<String> resultAssetIds;
    private Integer expectedReferenceCount;
    private Integer resolvedReferenceCount;
    private Integer appliedReferenceCount;
}
