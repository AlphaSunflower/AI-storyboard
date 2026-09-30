package com.moon.moonagent.ai.agent.creative;

import java.util.List;

public interface CreativeRunService {

    CreativePlan plan(String userId, String runId);

    List<MediaCritique> critiques(String userId, String runId);

    RepairTarget repairTarget(String userId, String runId, String itemId, String instruction);

    record RepairTarget(
            String conversationId,
            String prompt,
            List<String> referenceUrls
    ) {
        public RepairTarget {
            referenceUrls = referenceUrls == null ? List.of() : List.copyOf(referenceUrls);
        }
    }
}
