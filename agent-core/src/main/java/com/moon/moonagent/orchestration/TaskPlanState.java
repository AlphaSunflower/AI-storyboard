package com.moon.moonagent.orchestration;

public enum TaskPlanState {
    DRAFT,
    READY,
    RUNNING,
    WAITING_HUMAN,
    WAITING_ASYNC,
    REPLANNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
