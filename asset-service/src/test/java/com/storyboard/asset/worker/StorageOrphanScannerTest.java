package com.storyboard.asset.worker;

import com.storyboard.asset.cleanup.ObjectReferenceGuard;
import com.storyboard.asset.config.StorageProperties;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.storage.ObjectStorage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageOrphanScannerTest {

    private static StorageProperties properties(boolean enabled, int graceHours) {
        return new StorageProperties(
                "http://localhost:9000", "minioadmin", "minioadmin123", "storyboard", "us-east-1",
                0, 0, 0, 3, enabled, 6, graceHours);
    }

    private static ObjectStorage.ObjectSummary object(String key, Instant lastModified) {
        return new ObjectStorage.ObjectSummary(key, 10L, lastModified);
    }

    private static ObjectStorage.ObjectPage single(ObjectStorage.ObjectSummary object) {
        return new ObjectStorage.ObjectPage(List.of(object), null);
    }

    private static ObjectStorage.ObjectPage empty() {
        return new ObjectStorage.ObjectPage(List.of(), null);
    }

    @Test
    void enqueuesOnlyUnreferencedImageAndVideoObjects() {
        ObjectStorage storage = mock(ObjectStorage.class);
        ObjectReferenceGuard guard = mock(ObjectReferenceGuard.class);
        LibraryRepository repository = mock(LibraryRepository.class);
        Instant old = Instant.now().minus(48, ChronoUnit.HOURS);
        when(storage.listObjects("images/", null, StorageOrphanScanner.PAGE_SIZE))
                .thenReturn(new ObjectStorage.ObjectPage(
                        List.of(object("images/orphan.png", old), object("images/shared.png", old)), null));
        when(storage.listObjects("videos/", null, StorageOrphanScanner.PAGE_SIZE))
                .thenReturn(single(object("videos/orphan.mp4", old)));
        when(guard.referencedObjectKeys(anyCollection())).thenReturn(Set.of("images/shared.png"));

        new StorageOrphanScanner(storage, guard, repository, properties(true, 24)).scanOnce();

        verify(repository).createCleanupTask("images/orphan.png");
        verify(repository).createCleanupTask("videos/orphan.mp4");
        verify(repository, never()).createCleanupTask("images/shared.png");
    }

    @Test
    void skipsObjectsNewerThanGracePeriod() {
        ObjectStorage storage = mock(ObjectStorage.class);
        ObjectReferenceGuard guard = mock(ObjectReferenceGuard.class);
        LibraryRepository repository = mock(LibraryRepository.class);
        Instant recent = Instant.now().minus(1, ChronoUnit.HOURS);
        when(storage.listObjects("images/", null, StorageOrphanScanner.PAGE_SIZE))
                .thenReturn(single(object("images/fresh.png", recent)));
        when(storage.listObjects("videos/", null, StorageOrphanScanner.PAGE_SIZE)).thenReturn(empty());

        new StorageOrphanScanner(storage, guard, repository, properties(true, 24)).scanOnce();

        verify(repository, never()).createCleanupTask(anyString());
        verify(guard, never()).referencedObjectKeys(anyCollection());
    }

    @Test
    void doesNothingWhenScanDisabled() {
        ObjectStorage storage = mock(ObjectStorage.class);
        ObjectReferenceGuard guard = mock(ObjectReferenceGuard.class);
        LibraryRepository repository = mock(LibraryRepository.class);

        new StorageOrphanScanner(storage, guard, repository, properties(false, 24)).scanOnce();

        verify(storage, never()).listObjects(anyString(), nullable(String.class), anyInt());
        verify(repository, never()).createCleanupTask(anyString());
    }

    @Test
    void abortsWholeRoundWhenReferenceLookupFails() {
        ObjectStorage storage = mock(ObjectStorage.class);
        ObjectReferenceGuard guard = mock(ObjectReferenceGuard.class);
        LibraryRepository repository = mock(LibraryRepository.class);
        Instant old = Instant.now().minus(48, ChronoUnit.HOURS);
        when(storage.listObjects("images/", null, StorageOrphanScanner.PAGE_SIZE))
                .thenReturn(single(object("images/a.png", old)));
        when(storage.listObjects("videos/", null, StorageOrphanScanner.PAGE_SIZE)).thenReturn(empty());
        when(guard.referencedObjectKeys(anyCollection())).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() ->
                new StorageOrphanScanner(storage, guard, repository, properties(true, 24)).scanOnce())
                .doesNotThrowAnyException();

        verify(repository, never()).createCleanupTask(anyString());
    }

    @Test
    void abortsWholeRoundWhenListingFails() {
        ObjectStorage storage = mock(ObjectStorage.class);
        ObjectReferenceGuard guard = mock(ObjectReferenceGuard.class);
        LibraryRepository repository = mock(LibraryRepository.class);
        when(storage.listObjects("images/", null, StorageOrphanScanner.PAGE_SIZE))
                .thenThrow(new RuntimeException("minio down"));

        assertThatCode(() ->
                new StorageOrphanScanner(storage, guard, repository, properties(true, 24)).scanOnce())
                .doesNotThrowAnyException();

        verify(repository, never()).createCleanupTask(anyString());
    }

    @Test
    void followsContinuationTokens() {
        ObjectStorage storage = mock(ObjectStorage.class);
        ObjectReferenceGuard guard = mock(ObjectReferenceGuard.class);
        LibraryRepository repository = mock(LibraryRepository.class);
        Instant old = Instant.now().minus(48, ChronoUnit.HOURS);
        when(storage.listObjects("images/", null, StorageOrphanScanner.PAGE_SIZE))
                .thenReturn(new ObjectStorage.ObjectPage(
                        List.of(object("images/a.png", old)), "token-2"));
        when(storage.listObjects("images/", "token-2", StorageOrphanScanner.PAGE_SIZE))
                .thenReturn(single(object("images/b.png", old)));
        when(storage.listObjects("videos/", null, StorageOrphanScanner.PAGE_SIZE)).thenReturn(empty());
        when(guard.referencedObjectKeys(anyCollection())).thenReturn(Set.of());

        new StorageOrphanScanner(storage, guard, repository, properties(true, 24)).scanOnce();

        verify(repository).createCleanupTask("images/a.png");
        verify(repository).createCleanupTask("images/b.png");
    }
}