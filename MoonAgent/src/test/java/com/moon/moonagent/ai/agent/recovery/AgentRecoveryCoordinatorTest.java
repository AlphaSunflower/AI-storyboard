package com.moon.moonagent.ai.agent.recovery;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.timeout;

class AgentRecoveryCoordinatorTest {

    @Test
    void recoversClaimedCandidateAndReleasesLease() {
        AgentRecoveryProperties properties = properties();
        AgentRecoveryLeaseService leases = mock(AgentRecoveryLeaseService.class);
        when(leases.tryClaim(anyString(), anyString(), anyString())).thenReturn(1);
        AgentRecoverySource source = source(RecoveryOutcome.completed());
        AgentRecoveryCoordinator coordinator = coordinator(properties, leases, source);

        coordinator.scan();

        verify(leases, timeout(2_000)).release(any(RecoveryCandidate.class), anyString());
        verify(leases, never()).scheduleRetry(any(), anyString(), anyInt(), anyString());
    }

    @Test
    void schedulesRetryWithBackoffAndDoesNotBlockOtherLanes() {
        AgentRecoveryProperties properties = properties();
        AgentRecoveryLeaseService leases = mock(AgentRecoveryLeaseService.class);
        when(leases.tryClaim(anyString(), anyString(), anyString())).thenReturn(1);
        AgentRecoverySource source = source(RecoveryOutcome.retryable("temporary"));
        AgentRecoveryCoordinator coordinator = coordinator(properties, leases, source);

        coordinator.scan();

        verify(leases, timeout(2_000)).scheduleRetry(any(RecoveryCandidate.class), anyString(),
                org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.eq("temporary"));
    }

    @Test
    void conflictsDoNotInvokeRecovery() {
        AgentRecoveryProperties properties = properties();
        AgentRecoveryLeaseService leases = mock(AgentRecoveryLeaseService.class);
        when(leases.tryClaim(anyString(), anyString(), anyString())).thenReturn(0);
        AtomicInteger recoveries = new AtomicInteger();
        AgentRecoverySource source = new AgentRecoverySource() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public Duration interval() {
                return Duration.ZERO;
            }

            @Override
            public int batchSize() {
                return 1;
            }

            @Override
            public List<RecoveryCandidate> findCandidates(OffsetDateTime now, int limit) {
                return List.of(candidate());
            }

            @Override
            public RecoveryOutcome recover(RecoveryCandidate candidate, int attempt,
                                           String leaseOwner) {
                recoveries.incrementAndGet();
                return RecoveryOutcome.completed();
            }
        };
        AgentRecoveryCoordinator coordinator = coordinator(properties, leases, source);

        coordinator.scan();

        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(recoveries).hasValue(0);
    }

    private static AgentRecoveryCoordinator coordinator(AgentRecoveryProperties properties,
                                                        AgentRecoveryLeaseService leases,
                                                        AgentRecoverySource source) {
        return new AgentRecoveryCoordinator(
                properties, leases, List.of(source), new SimpleMeterRegistry());
    }

    private static AgentRecoverySource source(RecoveryOutcome outcome) {
        return new AgentRecoverySource() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public Duration interval() {
                return Duration.ZERO;
            }

            @Override
            public int batchSize() {
                return 1;
            }

            @Override
            public List<RecoveryCandidate> findCandidates(OffsetDateTime now, int limit) {
                return List.of(candidate());
            }

            @Override
            public RecoveryOutcome recover(RecoveryCandidate candidate, int attempt,
                                           String leaseOwner) {
                return outcome;
            }
        };
    }

    private static RecoveryCandidate candidate() {
        return new RecoveryCandidate("test", "1", "c1", Map.of());
    }

    private static AgentRecoveryProperties properties() {
        AgentRecoveryProperties properties = new AgentRecoveryProperties();
        properties.setMode(RecoveryMode.COORDINATOR);
        properties.setMaxParallel(1);
        return properties;
    }
}
