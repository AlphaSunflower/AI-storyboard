package com.moon.moonagent.ai.agent.recovery;

/**
 * Expected checkpoint expiry. The status transition must commit even though the
 * caller receives a business error and does not resume the checkpoint.
 */
public class CheckpointExpirationException extends CheckpointResumeException {

    public CheckpointExpirationException(String message) {
        super(40001, message);
    }
}
