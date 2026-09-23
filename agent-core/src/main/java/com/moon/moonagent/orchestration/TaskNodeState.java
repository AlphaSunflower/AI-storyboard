package com.moon.moonagent.orchestration;

public enum TaskNodeState {
    PENDING,
    READY,
    RUNNING,
    WAITING_HUMAN,
    WAITING_ASYNC,
    COMPLETED,
    FAILED,
    SKIPPED
}
