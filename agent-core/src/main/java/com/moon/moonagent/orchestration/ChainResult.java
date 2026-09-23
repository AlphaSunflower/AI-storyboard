package com.moon.moonagent.orchestration;

import java.util.Map;

public record ChainResult(
        ChainType chain,
        String status,
        String message,
        String nextPhase,
        Map<String, Object> state
) {
    public ChainResult {
        state = state == null ? Map.of() : Map.copyOf(state);
    }

    public static ChainResult done(ChainType chain, String message) {
        return new ChainResult(chain, "completed", message, "", Map.of());
    }
}
