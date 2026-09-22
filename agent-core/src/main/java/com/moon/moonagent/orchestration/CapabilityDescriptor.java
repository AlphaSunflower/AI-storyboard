package com.moon.moonagent.orchestration;

import java.util.List;

public record CapabilityDescriptor(
        String key,
        String title,
        String description,
        List<String> inputKeys,
        boolean async,
        HitlPolicy defaultHitlPolicy,
        EvaluationMode evaluationMode,
        int maxAttempts
) {
    public CapabilityDescriptor {
        inputKeys = inputKeys == null ? List.of() : List.copyOf(inputKeys);
        defaultHitlPolicy = defaultHitlPolicy == null ? HitlPolicy.NONE : defaultHitlPolicy;
        evaluationMode = evaluationMode == null ? EvaluationMode.DETERMINISTIC : evaluationMode;
        maxAttempts = maxAttempts <= 0 ? 3 : maxAttempts;
    }
}
