package com.storyboard.asset.worker;

import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.storage.ObjectStorage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageCleanupWorkerTest {

    @Test
    void keepsObjectWhenAnotherAssetStillReferencesIt() {
        LibraryRepository repository = mock(LibraryRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        StorageCleanupWorker worker = new StorageCleanupWorker(repository, storage);
        LibraryRepository.CleanupTask task = new LibraryRepository.CleanupTask("task-1", "images/shared.png", 1);
        when(repository.claimCleanupTasks(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(20)))
                .thenReturn(List.of(task));
        when(repository.objectKeyHasReferences("images/shared.png")).thenReturn(true);

        worker.run();

        verify(storage, never()).delete("images/shared.png");
        verify(repository).completeCleanup("task-1");
    }
}
