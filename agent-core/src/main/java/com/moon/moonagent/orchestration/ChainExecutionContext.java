package com.moon.moonagent.orchestration;

import java.util.List;
import java.util.Map;

public record ChainExecutionContext(
        String conversationId,
        String userId,
        String projectId,
        ChainType chain,
        String content,
        List<String> assetIds,
        Map<String, Object> state
) {
    public ChainExecutionContext {
        assetIds = assetIds == null ? List.of() : List.copyOf(assetIds);
        state = state == null ? Map.of() : Map.copyOf(state);
    }
}
