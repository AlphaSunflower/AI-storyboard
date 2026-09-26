package com.moon.moonagent.ai.agent.recovery;

public class CheckpointResumeException extends RuntimeException {

    private final int code;

    public CheckpointResumeException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
