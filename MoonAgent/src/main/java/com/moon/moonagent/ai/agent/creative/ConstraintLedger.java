package com.moon.moonagent.ai.agent.creative;

import java.util.List;

public record ConstraintLedger(
        CreativeMode mode,
        List<ConstraintItem> hard,
        List<ConstraintItem> soft,
        List<String> unresolved
) {
    public ConstraintLedger {
        mode = mode == null ? CreativeMode.BALANCED : mode;
        hard = hard == null ? List.of() : List.copyOf(hard);
        soft = soft == null ? List.of() : List.copyOf(soft);
        unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
    }

    public boolean strict() {
        return mode == CreativeMode.STRICT;
    }

    public List<ConstraintItem> all() {
        return java.util.stream.Stream.concat(hard.stream(), soft.stream()).toList();
    }

    public record ConstraintItem(
            String id,
            String statement,
            ConstraintSeverity severity,
            String source,
            String verifier,
            String value
    ) {
        public ConstraintItem {
            id = text(id);
            statement = text(statement);
            severity = severity == null ? ConstraintSeverity.SOFT : severity;
            source = text(source);
            verifier = text(verifier);
            value = text(value);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
