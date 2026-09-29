package com.storyboard.asset.worker;

import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.service.AssetPurgeService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrashPurgeWorkerTest {

    @Test
    void purgesEveryClaimedDueAsset() {
        LibraryRepository repository = mock(LibraryRepository.class);
        AssetPurgeService purgeService = mock(AssetPurgeService.class);
        when(repository.claimDueTrashAssets(anyInt())).thenReturn(List.of("a1", "a2"));
        when(purgeService.purgeDue(anyString())).thenReturn(AssetPurgeService.PurgeOutcome.PURGED);

        new TrashPurgeWorker(repository, purgeService).run();

        verify(purgeService).purgeDue("a1");
        verify(purgeService).purgeDue("a2");
    }

    @Test
    void keepsClaimedAssetWhenPurgeIsBlocked() {
        LibraryRepository repository = mock(LibraryRepository.class);
        AssetPurgeService purgeService = mock(AssetPurgeService.class);
        when(repository.claimDueTrashAssets(anyInt())).thenReturn(List.of("a1"));
        when(purgeService.purgeDue("a1")).thenReturn(AssetPurgeService.PurgeOutcome.BLOCKED);

        new TrashPurgeWorker(repository, purgeService).run();

        verify(purgeService).purgeDue("a1");
    }

    @Test
    void continuesWithRemainingAssetsWhenOneFails() {
        LibraryRepository repository = mock(LibraryRepository.class);
        AssetPurgeService purgeService = mock(AssetPurgeService.class);
        when(repository.claimDueTrashAssets(anyInt())).thenReturn(List.of("a1", "a2"));
        when(purgeService.purgeDue("a1")).thenThrow(new RuntimeException("db error"));
        when(purgeService.purgeDue("a2")).thenReturn(AssetPurgeService.PurgeOutcome.PURGED);

        new TrashPurgeWorker(repository, purgeService).run();

        verify(purgeService).purgeDue("a2");
    }

    @Test
    void doesNothingWhenNoAssetIsDue() {
        LibraryRepository repository = mock(LibraryRepository.class);
        AssetPurgeService purgeService = mock(AssetPurgeService.class);
        when(repository.claimDueTrashAssets(anyInt())).thenReturn(List.of());

        new TrashPurgeWorker(repository, purgeService).run();

        verify(purgeService, never()).purgeDue(anyString());
    }
}