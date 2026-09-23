package com.moon.moonagent.orchestration;

import java.util.List;
import java.util.Map;

public record TaskNode(
        String nodeId,
        String capabilityKey,
        String title,
        Map<String, Object> parameters,
        List<String> dependencies,
        TaskNodeState state,
        int attemptCount,
        HitlPolicy hitlPolicy,
        EvaluationMode evaluationMode,
        String qualityCriteria,
        String outputSummary,
        String asyncTaskId,
        String error
) {
    public TaskNode {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        state = state == null ? TaskNodeState.PENDING : state;
        hitlPolicy = hitlPolicy == null ? HitlPolicy.NONE : hitlPolicy;
        evaluationMode = evaluationMode == null ? EvaluationMode.DETERMINISTIC : evaluationMode;
        qualityCriteria = qualityCriteria == null ? "" : qualityCriteria;
        outputSummary = outputSummary == null ? "" : outputSummary;
        asyncTaskId = asyncTaskId == null ? "" : asyncTaskId;
        error = error == null ? "" : error;
    }
}
