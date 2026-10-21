package com.moon.moonagent.ai.agent.maintenance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 保留期清理的调度语义：dry-run 只统计、按批删到短批为止、单目标失败不影响其它目标。
 */
@ExtendWith(MockitoExtension.class)
class DataRetentionServiceTest {

    @Mock private DataRetentionRepository repository;

    private RetentionProperties properties;
    private DataRetentionService service;

    @BeforeEach
    void setUp() {
        properties = new RetentionProperties();
        properties.setTurnEventsDays(30);
        properties.setSubagentRunsDays(90);
        properties.setOrphanSessionsDays(7);
        properties.setOutboxDays(7);
        service = new DataRetentionService(repository, properties);
    }

    @Test
    void dryRunCountsButNeverDeletes() {
        properties.setDryRun(true);
        when(repository.countTurnEventsOlderThan(30)).thenReturn(1234L);
        when(repository.countSubagentRunsOlderThan(90)).thenReturn(0L);
        when(repository.countTerminalOutboxOlderThan(7)).thenReturn(0L);
        when(repository.countOrphanSessionStatesOlderThan(7)).thenReturn(0L);

        assertThat(service.runOnce()).isZero();

        verify(repository, never()).deleteTurnEventsOlderThan(anyInt(), anyInt());
        verify(repository, never()).deleteSubagentRunsOlderThan(anyInt(), anyInt());
        verify(repository, never()).deleteTerminalOutboxOlderThan(anyInt(), anyInt());
        verify(repository, never()).deleteOrphanSessionStatesOlderThan(anyInt(), anyInt());
    }

    @Test
    void deletesInBatchesUntilShortBatch() {
        properties.setDryRun(false);
        properties.setBatchSize(1000);
        properties.setMaxBatches(5);
        when(repository.countTurnEventsOlderThan(30)).thenReturn(2500L);
        when(repository.deleteTurnEventsOlderThan(30, 1000)).thenReturn(1000, 1000, 500);
        when(repository.countSubagentRunsOlderThan(90)).thenReturn(0L);
        when(repository.countTerminalOutboxOlderThan(7)).thenReturn(0L);
        when(repository.countOrphanSessionStatesOlderThan(7)).thenReturn(0L);

        assertThat(service.runOnce()).isEqualTo(2500);

        // 1000 + 1000 + 500：短批即收工，不再发第 4 条（即使还差 maxBatches）
        verify(repository, times(3)).deleteTurnEventsOlderThan(30, 1000);
    }

    @Test
    void zeroRetentionDisablesTargetWithoutTouchingDatabase() {
        properties.setTurnEventsDays(0);
        properties.setSubagentRunsDays(0);
        properties.setOrphanSessionsDays(0);
        properties.setOutboxDays(0);

        assertThat(service.runOnce()).isZero();

        verifyNoInteractions(repository);
    }

    @Test
    void oneFailingTargetDoesNotAbortOthers() {
        properties.setDryRun(false);
        when(repository.countTurnEventsOlderThan(30))
                .thenThrow(new IllegalStateException("relation \"agent_turn_events\" does not exist"));
        when(repository.countTerminalOutboxOlderThan(7)).thenReturn(20L);
        when(repository.deleteTerminalOutboxOlderThan(7, properties.getBatchSize())).thenReturn(20);
        when(repository.countSubagentRunsOlderThan(90)).thenReturn(0L);
        when(repository.countOrphanSessionStatesOlderThan(7)).thenReturn(0L);

        assertThat(service.runOnce()).isEqualTo(20);

        verify(repository).deleteTerminalOutboxOlderThan(7, properties.getBatchSize());
    }

    @Test
    void disabledJobDoesNothing() {
        properties.setEnabled(false);

        assertThat(service.runOnce()).isZero();

        verifyNoInteractions(repository);
    }
}
