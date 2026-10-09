package com.moon.moonagent.ai.agent.recovery;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
public class AgentRecoveryCoordinator {

    private final AgentRecoveryProperties properties;
    private final AgentRecoveryLeaseService leases;
    private final List<AgentRecoverySource> sources;
    private final MeterRegistry meters;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, Long> nextRunAtNanos = new ConcurrentHashMap<>();
    private final java.util.Set<String> runningSources = ConcurrentHashMap.newKeySet();
    private final String owner = UUID.randomUUID().toString();

    public AgentRecoveryCoordinator(AgentRecoveryProperties properties,
                                    AgentRecoveryLeaseService leases,
                                    List<AgentRecoverySource> sources,
                                    MeterRegistry meters) {
        this.properties = properties;
        this.leases = leases;
        this.sources = sources == null ? List.of() : List.copyOf(sources);
        this.meters = meters;
        this.executor = Executors.newFixedThreadPool(properties.getMaxParallel(), runnable -> {
            Thread thread = new Thread(runnable, "agent-recovery");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Scheduled(fixedDelayString = "${ai.agent.recovery.scan-delay:5s}")
    public void scan() {
        if (properties.getMode() != RecoveryMode.COORDINATOR) return;
        long now = System.nanoTime();
        for (AgentRecoverySource source : sources) {
            if (!due(source, now)) continue;
            if (!runningSources.add(source.name())) continue;
            CompletableFuture.runAsync(() -> runSource(source), executor)
                    .whenComplete((ignored, error) -> runningSources.remove(source.name()));
        }
    }

    @PostConstruct
    void initializeMetrics() {
        for (AgentRecoverySource source : sources) {
            counter("agent_recovery_claimed_total", source.name());
            counter("agent_recovery_completed_total", source.name());
            counter("agent_recovery_failed_total", source.name());
            counter("agent_recovery_lease_conflict_total", source.name());
        }
    }

    private boolean due(AgentRecoverySource source, long now) {
        Long current = nextRunAtNanos.get(source.name());
        if (current == null) {
            nextRunAtNanos.put(source.name(), now + source.interval().toNanos());
            return true;
        }
        long next = current;
        if (now < next) return false;
        return nextRunAtNanos.replace(source.name(), next,
                now + source.interval().toNanos());
    }

    private void runSource(AgentRecoverySource source) {
        try {
            List<RecoveryCandidate> candidates =
                    source.findCandidates(OffsetDateTime.now(), source.batchSize());
            for (RecoveryCandidate candidate : candidates) {
                int attempt = leases.tryClaim(
                        candidate.resourceType(), candidate.resourceId(), owner);
                if (attempt == 0) {
                    counter("agent_recovery_lease_conflict_total", source.name()).increment();
                    continue;
                }
                counter("agent_recovery_claimed_total", source.name()).increment();
                process(source, candidate, attempt);
            }
        } catch (Exception e) {
            log.warn("agent recovery lane scan failed lane={} error={}",
                    source.name(), e.getMessage(), e);
            counter("agent_recovery_failed_total", source.name()).increment();
        }
    }

    private void process(AgentRecoverySource source, RecoveryCandidate candidate, int attempt) {
        long started = System.nanoTime();
        RecoveryOutcome outcome;
        try {
            outcome = attempt >= properties.getMaxAttempts()
                    ? source.exhausted(candidate, "recovery attempts exhausted", owner)
                    : source.recover(candidate, attempt, owner);
            if (outcome == null) outcome = RecoveryOutcome.retryable("recovery returned no outcome");
        } catch (Exception e) {
            outcome = RecoveryOutcome.retryable(e.getMessage());
        }

        try {
            switch (outcome.status()) {
                case COMPLETED, DEFERRED, TERMINAL_FAILURE -> leases.release(candidate, owner);
                case RETRYABLE_FAILURE -> {
                    if (attempt >= properties.getMaxAttempts()) {
                        RecoveryOutcome exhausted =
                                source.exhausted(candidate, outcome.message(), owner);
                        if (exhausted != null
                                && exhausted.status() == RecoveryOutcome.Status.TERMINAL_FAILURE) {
                            leases.release(candidate, owner);
                        } else {
                            leases.scheduleRetry(candidate, owner, attempt, outcome.message());
                        }
                    } else {
                        leases.scheduleRetry(candidate, owner, attempt, outcome.message());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("agent recovery lease update failed lane={} resourceId={} attempt={} error={}",
                    source.name(), candidate.resourceId(), attempt, e.getMessage(), e);
        }

        long durationMs = (System.nanoTime() - started) / 1_000_000L;
        if (outcome.status() == RecoveryOutcome.Status.COMPLETED
                || outcome.status() == RecoveryOutcome.Status.DEFERRED) {
            counter("agent_recovery_completed_total", source.name()).increment();
        } else {
            counter("agent_recovery_failed_total", source.name()).increment();
        }
        Timer.builder("agent_recovery_duration")
                .tag("lane", source.name())
                .register(meters)
                .record(java.time.Duration.ofMillis(durationMs));
        log.info("agent recovery lane={} resourceType={} resourceId={} attempt={} " +
                        "leaseOwner={} durationMs={} outcome={} message={}",
                source.name(), candidate.resourceType(), candidate.resourceId(), attempt,
                owner, durationMs, outcome.status(), outcome.message());
    }

    private Counter counter(String name, String lane) {
        return Counter.builder(name).tag("lane", lane).register(meters);
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }
}
