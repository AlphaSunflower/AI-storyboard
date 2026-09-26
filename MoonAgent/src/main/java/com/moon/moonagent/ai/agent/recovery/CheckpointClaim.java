package com.moon.moonagent.ai.agent.recovery;

import com.moon.moonagent.entity.AgentCheckpoint;

public record CheckpointClaim(
        AgentCheckpoint checkpoint,
        ResumeCommand command,
        String leaseOwner) {
}
