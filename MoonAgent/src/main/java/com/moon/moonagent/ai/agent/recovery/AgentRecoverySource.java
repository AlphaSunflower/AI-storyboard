package com.moon.moonagent.ai.agent.recovery;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

public interface AgentRecoverySource {

    String name();

    Duration interval();

    int batchSize();

    List<RecoveryCandidate> findCandidates(OffsetDateTime now, int limit);

    RecoveryOutcome recover(RecoveryCandidate candidate, int attempt, String leaseOwner);

    default RecoveryOutcome exhausted(RecoveryCandidate candidate, String error,
                                      String leaseOwner) {
        return exhausted(candidate, error);
    }

    default RecoveryOutcome exhausted(RecoveryCandidate candidate, String error) {
        return RecoveryOutcome.terminal(error);
    }
}
