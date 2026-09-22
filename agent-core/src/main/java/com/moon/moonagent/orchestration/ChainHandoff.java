package com.moon.moonagent.orchestration;

import java.util.Map;

public record ChainHandoff(
        String id,
        String loopRunId,
        ChainType from,
        ChainType to,
        String reason,
        Map<String, Object> state
) {
    public ChainHandoff {
        state = state == null ? Map.of() : Map.copyOf(state);
    }
}
