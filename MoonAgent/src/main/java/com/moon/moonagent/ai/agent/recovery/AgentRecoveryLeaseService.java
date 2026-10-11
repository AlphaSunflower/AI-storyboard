package com.moon.moonagent.ai.agent.recovery;

import com.moon.moonagent.mapper.AgentRecoveryLeaseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class AgentRecoveryLeaseService {

    private final AgentRecoveryLeaseMapper mapper;
    private final AgentRecoveryProperties properties;

    public int tryClaim(String resourceType, String resourceId, String owner) {
        OffsetDateTime now = OffsetDateTime.now();
        Integer attempt = mapper.tryClaim(
                resourceType, resourceId, owner, now.plus(properties.getLeaseDuration()));
        return attempt == null ? 0 : attempt;
    }

    public void scheduleRetry(RecoveryCandidate candidate, String owner,
                              int attempt, String error) {
        OffsetDateTime nextAttempt = OffsetDateTime.now().plus(backoff(attempt));
        mapper.scheduleRetry(candidate.resourceType(), candidate.resourceId(),
                owner, nextAttempt, truncate(error));
    }

    public void release(RecoveryCandidate candidate, String owner) {
        mapper.release(candidate.resourceType(), candidate.resourceId(), owner);
    }

    public void releaseAny(RecoveryCandidate candidate) {
        mapper.releaseAny(candidate.resourceType(), candidate.resourceId());
    }

    public boolean owns(RecoveryCandidate candidate, String owner) {
        return mapper.ownedBy(candidate.resourceType(), candidate.resourceId(), owner) > 0;
    }

    public boolean hasActiveLease(String resourceType, String resourceId) {
        return mapper.hasActiveLease(resourceType, resourceId) > 0;
    }

    public int attempt(RecoveryCandidate candidate, String owner) {
        Integer attempt = mapper.attemptFor(candidate.resourceType(), candidate.resourceId(), owner);
        return attempt == null ? 1 : Math.max(1, attempt);
    }

    public Duration backoff(int attempt) {
        long multiplier = 1L << Math.min(Math.max(0, attempt - 1), 20);
        Duration value = properties.getBaseBackoff().multipliedBy(multiplier);
        return value.compareTo(properties.getMaxBackoff()) > 0
                ? properties.getMaxBackoff() : value;
    }

    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }
}
