package com.storyboard.asset.service;

import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetPurgeServiceTest {

    private LibraryRepository repository;
    private AssetPurgeService service;

    @BeforeEach
    void setUp() {
        repository = mock(LibraryRepository.class);
        service = new AssetPurgeService(repository);
    }

    @Test
    void purgeManuallyUnlinksThenEnqueuesAndMarksPurged() {
        when(repository.hasActiveReferences("a1")).thenReturn(false);
        when(repository.markPurgePending("a1")).thenReturn(true);

        service.purgeManually("a1");

        verify(repository).unlinkAllProjects("a1");
        verify(repository).markPurgePending("a1");
        verify(repository).markPurged("a1");
        verify(repository, never()).markPurgeBlocked("a1");
    }

    @Test
    void purgeManuallyBlocksWhenActiveReferencesRemain() {
        when(repository.hasActiveReferences("a1")).thenReturn(true);

        assertThatThrownBy(() -> service.purgeManually("a1"))
                .isInstanceOf(BusinessException.class);

        verify(repository).markPurgeBlocked("a1");
        verify(repository, never()).markPurgePending("a1");
        verify(repository, never()).markPurged("a1");
    }

    @Test
    void purgeDueDoesNotUnlinkProjectsAndBlocksWhenStillReferenced() {
        when(repository.hasActiveReferences("a1")).thenReturn(true);

        assertThat(service.purgeDue("a1")).isEqualTo(AssetPurgeService.PurgeOutcome.BLOCKED);

        verify(repository, never()).unlinkAllProjects("a1");
        verify(repository).markPurgeBlocked("a1");
        verify(repository, never()).markPurged("a1");
    }

    @Test
    void purgeDueEnqueuesAndMarksPurgedWhenNoReferenceRemains() {
        when(repository.hasActiveReferences("a1")).thenReturn(false);
        when(repository.markPurgePending("a1")).thenReturn(true);

        assertThat(service.purgeDue("a1")).isEqualTo(AssetPurgeService.PurgeOutcome.PURGED);

        verify(repository, never()).unlinkAllProjects("a1");
        verify(repository).markPurged("a1");
    }

    @Test
    void purgeDueSkipsWhenStateAlreadyChanged() {
        when(repository.hasActiveReferences("a1")).thenReturn(false);
        when(repository.markPurgePending("a1")).thenReturn(false);

        assertThat(service.purgeDue("a1")).isEqualTo(AssetPurgeService.PurgeOutcome.SKIPPED);

        verify(repository, never()).markPurged("a1");
    }
}