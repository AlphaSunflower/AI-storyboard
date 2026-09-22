package com.moon.moonagent.ai.agent.orchestration;

public class InvalidTaskPlanTransitionException extends IllegalArgumentException {
    public InvalidTaskPlanTransitionException(String message) {
        super(message);
    }
}
