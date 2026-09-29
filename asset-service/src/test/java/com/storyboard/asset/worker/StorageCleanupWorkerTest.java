package com.storyboard.asset.worker;

import com.storyboard.asset.cleanup.ObjectReferenceGuard;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.storage.ObjectStorage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageCleanupWorkerTest {

    private static LibraryRepository.CleanupTask task() {
        return new LibraryRepository.CleanupTask("task-1", "images/shared.png", 1);
    }

    @Test
    void keepsObjectWhenAnotherAssetStillReferencesIt() {
        LibraryRepository repository = mock(LibraryRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        ObjectReferenceGuard guard = mock(ObjectReferenceGuard.class);
        StorageCleanupWorker worker = new StorageCleanupWorker(repository, storage, guard);
        when(repository.claimCleanupTasks(anyString(), eq(20))).thenReturn(List.of(task()));
        when(guard.isReferenced("images/shared.png")).thenReturn(true);

        worker.run();

        verify(storage, never()).delete("images/shared.png");
        verify(repository).completeCleanup("task-1");
    }

    @Test
    void deletesObjectWhenNoReferenceRemains() {
        LibraryRepository repository = mock(LibraryRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        ObjectReferenceGuard guard = mock(ObjectReferenceGuard.class);
        StorageCleanupWorker worker = new StorageCleanupWorker(repository, storage, guard);
        when(repository.claimCleanupTasks(anyString(), eq(20))).thenReturn(List.of(task()));
        when(guard.isReferenced("images/shared.png")).thenReturn(false);
        when(storage.exists("images/shared.png")).thenReturn(true);

        worker.run();

        verify(storage).delete("images/shared.png");
        verify(repository).completeCleanup("task-1");
    }

    @Test
    void recordsFailureWhenStorageDeleteThrows() {
        LibraryRepository repository = mock(LibraryRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        ObjectReferenceGuard guard = mock(ObjectReferenceGuard.class);
        StorageCleanupWorker worker = new StorageCleanupWorker(repository, storage, guard);
        when(repository.claimCleanupTasks(anyString(), eq(20))).thenReturn(List.of(task()));
        when(guard.isReferenced("images/shared.png")).thenReturn(false);
        when(storage.exists("images/shared.png")).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("s3 down"))
                .when(storage).delete("images/shared.png");

        worker.run();

        verify(repository).failCleanup("task-1", 1, "s3 down");
        verify(repository, never()).completeCleanup("task-1");
    }
}