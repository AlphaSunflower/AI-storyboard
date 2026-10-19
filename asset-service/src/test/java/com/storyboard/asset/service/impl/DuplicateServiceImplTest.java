package com.storyboard.asset.service.impl;

import com.storyboard.asset.client.AssetContracts.AssetDetail;
import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetStatus;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.MergeDuplicatesRequest;
import com.storyboard.asset.client.AssetContracts.MetadataState;
import com.storyboard.asset.config.StorageProperties;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.service.AssetCatalogService;
import com.storyboard.asset.service.ProjectAccessPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DuplicateServiceImplTest {

    private LibraryRepository repository;
    private ProjectAccessPort projectAccess;
    private DuplicateServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(LibraryRepository.class);
        projectAccess = mock(ProjectAccessPort.class);
        AssetCatalogService catalogService = mock(AssetCatalogService.class);
        service = new DuplicateServiceImpl(
                repository, catalogService, projectAccess,
                new StorageProperties(null, null, null, null, null, 0, 0, 0, 3, false, 6, 24));
    }

    @Test
    void mergeMovesReferencesAndTrashesDuplicates() {
        AssetSummary survivor = asset("survivor", 1);
        AssetSummary duplicate = asset("duplicate", 2);
        when(repository.findAsset("u1", "survivor")).thenReturn(Optional.of(survivor));
        when(repository.findAsset("u1", "duplicate")).thenReturn(Optional.of(duplicate));
        when(repository.shareBlob("survivor", "duplicate")).thenReturn(true);
        when(repository.trash(any(), any(), anyLong(), any())).thenReturn(true);
        AssetCatalogService catalogService = mock(AssetCatalogService.class);
        when(catalogService.get("u1", "survivor"))
                .thenReturn(new AssetDetail(survivor, List.of(), List.of()));
        service = new DuplicateServiceImpl(
                repository, catalogService, projectAccess,
                new StorageProperties(null, null, null, null, null, 0, 0, 0, 3, false, 6, 24));

        AssetSummary merged = service.merge("u1", new MergeDuplicatesRequest("survivor", List.of("duplicate")));

        assertThat(merged.id()).isEqualTo("survivor");
        verify(repository).copyMissingMedia("survivor", "duplicate");
        verify(repository).moveFolderMappings("survivor", "duplicate");
        verify(repository).moveTags("survivor", "duplicate");
        verify(repository).moveProjectLinks("survivor", "duplicate");
        verify(repository).moveGroupItems("survivor", "duplicate");
        verify(repository).moveGroupOutputs("survivor", "duplicate");
        verify(repository).trash(any(), any(), anyLong(), any());
    }

    @Test
    void mergeRejectsAssetsThatDoNotShareTheSameBlob() {
        when(repository.findAsset("u1", "survivor")).thenReturn(Optional.of(asset("survivor", 1)));
        when(repository.findAsset("u1", "duplicate")).thenReturn(Optional.of(asset("duplicate", 1)));
        when(repository.shareBlob("survivor", "duplicate")).thenReturn(false);

        assertThatThrownBy(() -> service.merge(
                "u1", new MergeDuplicatesRequest("survivor", List.of("duplicate"))))
                .hasMessageContaining("不是同一份文件");
        verify(repository, never()).trash(any(), any(), anyLong(), any());
    }

    @Test
    void mergeRequiresDuplicateSelection() {
        assertThatThrownBy(() -> service.merge(
                "u1", new MergeDuplicatesRequest("survivor", List.of())))
                .hasMessageContaining("请选择要合并的重复素材");
    }

    private static AssetSummary asset(String id, long version) {
        return new AssetSummary(
                id, "u1", AssetScope.PROJECT, "p1", AssetType.IMAGE, null,
                id + ".png", null, null, false, MetadataState.NONE, AssetStatus.ACTIVE,
                "/api/files/images/" + id + ".png", version, List.of(), List.of(),
                "generation", "record-" + id, OffsetDateTime.now(), OffsetDateTime.now());
    }
}
