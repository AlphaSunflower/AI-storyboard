package com.storyboard.asset.cleanup;

import com.storyboard.asset.repository.LegacyObjectReferenceRepository;
import com.storyboard.asset.repository.LibraryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObjectReferenceGuardTest {

    private LibraryRepository libraryRepository;
    private LegacyObjectReferenceRepository legacyRepository;
    private ObjectReferenceGuard guard;

    @BeforeEach
    void setUp() {
        libraryRepository = mock(LibraryRepository.class);
        legacyRepository = mock(LegacyObjectReferenceRepository.class);
        guard = new ObjectReferenceGuard(libraryRepository, legacyRepository);
    }

    @Test
    void unionsLibraryUploadSessionAndLegacyReferences() {
        when(libraryRepository.referencedObjectKeys(anyCollection())).thenReturn(Set.of("images/lib.png"));
        when(libraryRepository.activeUploadSessionObjectKeys(anyCollection()))
                .thenReturn(Set.of("images/uploading.png"));
        when(legacyRepository.referencedKeys(anyCollection())).thenReturn(Set.of("images/legacy.png"));

        Set<String> referenced = guard.referencedObjectKeys(
                List.of("images/lib.png", "images/uploading.png", "images/legacy.png", "images/orphan.png"));

        assertThat(referenced).containsExactlyInAnyOrder(
                "images/lib.png", "images/uploading.png", "images/legacy.png");
    }

    @Test
    void skipsLaterSourcesOnceEverythingIsCovered() {
        when(libraryRepository.referencedObjectKeys(anyCollection())).thenReturn(Set.of("images/a.png"));

        assertThat(guard.referencedObjectKeys(List.of("images/a.png"))).containsExactly("images/a.png");

        verify(legacyRepository, never()).referencedKeys(anyCollection());
    }

    @Test
    void isReferencedDelegatesToBatchCheck() {
        when(libraryRepository.referencedObjectKeys(anyCollection())).thenReturn(Set.of("videos/a.mp4"));

        assertThat(guard.isReferenced("videos/a.mp4")).isTrue();
        assertThat(guard.isReferenced("videos/b.mp4")).isFalse();
    }

    @Test
    void emptyOrBlankCandidatesDoNotTouchRepositories() {
        assertThat(guard.referencedObjectKeys(List.of())).isEmpty();
        assertThat(guard.referencedObjectKeys(List.of("  "))).isEmpty();
        assertThat(guard.isReferenced(null)).isFalse();
        assertThat(guard.isReferenced("")).isFalse();

        verify(libraryRepository, never()).referencedObjectKeys(anyCollection());
    }

    @Test
    void propagatesSourceFailureInsteadOfSwallowingIt() {
        when(libraryRepository.referencedObjectKeys(anyCollection())).thenReturn(Set.of());
        when(libraryRepository.activeUploadSessionObjectKeys(anyCollection()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> guard.referencedObjectKeys(List.of("images/a.png")))
                .isInstanceOf(IllegalStateException.class);
    }
}