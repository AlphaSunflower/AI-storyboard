package com.storyboard.asset.service.impl;

import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.LibrarySavedView;
import com.storyboard.asset.client.AssetContracts.SaveViewRequest;
import com.storyboard.asset.client.AssetContracts.UpdateSavedViewRequest;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.service.ProjectAccessPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SavedViewServiceImplTest {

    private LibraryRepository repository;
    private ProjectAccessPort projectAccess;
    private SavedViewServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(LibraryRepository.class);
        projectAccess = mock(ProjectAccessPort.class);
        service = new SavedViewServiceImpl(repository, projectAccess);
    }

    @Test
    void createPersistsSanitizedFiltersAndReturnsSavedView() {
        when(repository.insertSavedView(
                eq("u1"), eq("p1"), eq("五星精选"), eq("五星精选"),
                anyString(), anyString(), eq("folders"), anyBoolean()))
                .thenReturn("v1");
        when(repository.findSavedView("u1", "v1")).thenReturn(Optional.of(view()));

        LibrarySavedView created = service.create("u1", new SaveViewRequest(
                "p1", "五星精选",
                Map.of("minRating", 5, "bogus", "drop-me", "type", "IMAGE"),
                Map.of("field", "updatedAt", "direction", "desc"),
                "folders", true));

        verify(projectAccess).requireOwned("u1", "p1");
        assertThat(created.id()).isEqualTo("v1");
        assertThat(created.filters()).containsEntry("minRating", 5);
        assertThat(created.filters()).doesNotContainKey("bogus");
    }

    @Test
    void updateRejectsUnknownView() {
        when(repository.findSavedView("u1", "missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(
                "u1", "missing", new UpdateSavedViewRequest("x", Map.of(), Map.of(), "folders", false)))
                .hasMessageContaining("视图不存在");
    }

    @Test
    void deleteRequiresExistingView() {
        when(repository.deleteSavedView("u1", "missing")).thenReturn(false);

        assertThatThrownBy(() -> service.delete("u1", "missing"))
                .hasMessageContaining("视图不存在");
    }

    @Test
    void createRequiresProjectOwnership() {
        assertThatThrownBy(() -> service.create("u1", new SaveViewRequest(
                "", "我的视图", Map.of(), Map.of(), "folders", false)))
                .hasMessageContaining("请先选择项目");
    }

    private static LibrarySavedView view() {
        return new LibrarySavedView(
                "v1", "u1", AssetScope.PROJECT, "p1", "五星精选",
                Map.of("minRating", 5, "type", "IMAGE"),
                Map.of("field", "updatedAt", "direction", "desc"),
                "folders", true, OffsetDateTime.now(), OffsetDateTime.now());
    }
}
