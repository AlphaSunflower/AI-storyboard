package com.moon.moonagent.orchestration;

import java.util.Locale;

public enum OrchestrationMode {
    LEGACY("plan-graph-v1"),
    UNIFIED_LOOP("unified-loop-v1"),
    AGENTSCOPE("agentscope-java-2.0.3");

    private final String engineVersion;

    OrchestrationMode(String engineVersion) {
        this.engineVersion = engineVersion;
    }

    public String engineVersion() {
        return engineVersion;
    }

    public static OrchestrationMode from(String value) {
        if (value == null || value.isBlank()) return LEGACY;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return LEGACY;
        }
    }
}
