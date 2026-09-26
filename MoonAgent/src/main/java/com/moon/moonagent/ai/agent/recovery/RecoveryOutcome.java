package com.moon.moonagent.ai.agent.recovery;

public record RecoveryOutcome(Status status, String message) {

    public enum Status {
        COMPLETED,
        DEFERRED,
        RETRYABLE_FAILURE,
        TERMINAL_FAILURE
    }

    public static RecoveryOutcome completed() {
        return new RecoveryOutcome(Status.COMPLETED, "");
    }

    public static RecoveryOutcome deferred(String message) {
        return new RecoveryOutcome(Status.DEFERRED, message == null ? "" : message);
    }

    public static RecoveryOutcome retryable(String message) {
        return new RecoveryOutcome(Status.RETRYABLE_FAILURE,
                message == null ? "retryable recovery failure" : message);
    }

    public static RecoveryOutcome terminal(String message) {
        return new RecoveryOutcome(Status.TERMINAL_FAILURE,
                message == null ? "terminal recovery failure" : message);
    }
}
