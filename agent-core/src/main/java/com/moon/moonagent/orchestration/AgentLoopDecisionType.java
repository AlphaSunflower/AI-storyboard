package com.moon.moonagent.orchestration;

public enum AgentLoopDecisionType {
    CONTINUE,
    REPLAN,
    ASK_USER,
    WAIT_ASYNC,
    FINISH,
    FAIL
}
