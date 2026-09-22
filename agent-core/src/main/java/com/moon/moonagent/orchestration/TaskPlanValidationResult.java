package com.moon.moonagent.orchestration;

import java.util.List;

public record TaskPlanValidationResult(boolean valid, List<String> errors) {
    public TaskPlanValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static TaskPlanValidationResult ok() {
        return new TaskPlanValidationResult(true, List.of());
    }

    public static TaskPlanValidationResult invalid(List<String> errors) {
        return new TaskPlanValidationResult(false, errors);
    }
}
