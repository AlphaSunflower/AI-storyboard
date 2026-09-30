package com.storyboard.asset.service.impl;

import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.CreateAssetRequest;
import com.storyboard.asset.client.AssetContracts.DuplicateAssetsRequest;
import com.storyboard.asset.client.AssetContracts.MediaRef;
import com.storyboard.asset.client.AssetContracts.MediaRole;
import com.storyboard.asset.client.AssetContracts.MediaType;
import com.storyboard.asset.config.StorageProperties;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.security.MediaUrlSigner;
import com.storyboard.asset.service.AssetCatalogService;
import com.storyboard.asset.service.AssetPurgeService;
import com.storyboard.asset.service.ProjectAccessPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetCatalogServiceImplTest {

    private LibraryRepository repository;
    private ProjectAccessPort projectAccess;
    private AssetCatalogServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(LibraryRepository.class);
        projectAccess = mock(ProjectAccessPort.class);
        service = new AssetCatalogServiceImpl(
                repository, projectAccess, mock(MediaUrlSigner.class),
                new AssetPurgeService(repository),
                new StorageProperties(null, null, null, null, null, 0, 0, 0, 3, false, 6, 24));
    }

    @Test
    void projectAssetRequiresOwnershipValidation() {
        when(repository.insertAsset(
                anyString(), any(), nullable(String.class), any(), nullable(com.storyboard.asset.client.AssetContracts.AiAssetKind.class),
                anyString(), nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenReturn("a1");
        when(repository.findAsset("u1", "a1")).thenReturn(Optional.of(asset("a1")));

        service.create("u1", new CreateAssetRequest(
                "u1", "p1", "IMAGE", null, "demo", null, "upload"), "idem");

        verify(projectAccess).requireOwned("u1", "p1");
    }

    @Test
    void globalAssetSkipsProjectOwnershipValidation() {
        when(repository.insertAsset(
                anyString(), any(), nullable(String.class), any(), nullable(com.storyboard.asset.client.AssetContracts.AiAssetKind.class),
                anyString(), nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenReturn("a1");
        when(repository.findAsset("u1", "a1")).thenReturn(Optional.of(asset("a1")));

        service.create("u1", new CreateAssetRequest(
                "u1", null, "IMAGE", null, "demo", null, "upload"), "idem");

        verify(projectAccess, never()).requireOwned(anyString(), anyString());
    }

    @Test
    void imageMediaRegistrationQueuesAnalysis() {
        when(repository.findAsset("u1", "a1")).thenReturn(Optional.of(asset("a1")));
        when(repository.upsertBlob(anyString(), anyString(), anyString(), anyLong(), any(), any(), any(), any()))
                .thenReturn("b1");
        when(repository.upsertMedia(anyString(), anyString(), anyInt(), any(), any(Boolean.class), any()))
                .thenReturn("m1");
        when(repository.insertAsset(
                anyString(), any(), nullable(String.class), any(), nullable(com.storyboard.asset.client.AssetContracts.AiAssetKind.class),
                anyString(), nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenReturn("a1");

        service.registerMedia("u1", new AssetCatalogService.MediaRegistration(
                "p1", AssetScope.PROJECT, AssetType.IMAGE, "image", null, "upload", null,
                "images/a.png", "a".repeat(64), "image/png", 10, 1, 1, null,
                MediaType.IMAGE, MediaRole.REFERENCE, true, null, 0), "idem");

        verify(repository).createAnalysisTask("a1");
    }

    @Test
    void globalImageMediaRegistrationDoesNotQueueAnalysis() {
        when(repository.findAsset("u1", "a1")).thenReturn(Optional.of(asset("a1")));
        when(repository.upsertBlob(anyString(), anyString(), anyString(), anyLong(), any(), any(), any(), any()))
                .thenReturn("b1");
        when(repository.upsertMedia(anyString(), anyString(), anyInt(), any(), any(Boolean.class), any()))
                .thenReturn("m1");
        when(repository.insertAsset(
                anyString(), any(), nullable(String.class), any(), nullable(com.storyboard.asset.client.AssetContracts.AiAssetKind.class),
                anyString(), nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenReturn("a1");

        service.registerMedia("u1", new AssetCatalogService.MediaRegistration(
                null, AssetScope.GLOBAL, AssetType.IMAGE, "image", null, "upload", null,
                "images/a.png", "a".repeat(64), "image/png", 10, 1, 1, null,
                MediaType.IMAGE, MediaRole.REFERENCE, true, null, 0), "idem-global");

        verify(repository, never()).createAnalysisTask(anyString());
    }

    @Test
    void purgeUnlinksProjectsAndMarksAssetPurged() {
        when(repository.findAsset("u1", "a1"))
                .thenReturn(Optional.of(asset("a1", com.storyboard.asset.client.AssetContracts.AssetStatus.TRASHED)));
        when(repository.hasActiveReferences("a1")).thenReturn(false);
        when(repository.markPurgePending("a1")).thenReturn(true);

        service.purge("u1", "a1");

        verify(repository).unlinkAllProjects("a1");
        verify(repository).markPurgePending("a1");
        verify(repository).markPurged("a1");
    }

    @Test
    void purgeKeepsReferencedAssetBlocked() {
        when(repository.findAsset("u1", "a1"))
                .thenReturn(Optional.of(asset("a1", com.storyboard.asset.client.AssetContracts.AssetStatus.TRASHED)));
        when(repository.hasActiveReferences("a1")).thenReturn(true);

        assertThatThrownBy(() -> service.purge("u1", "a1"))
                .isInstanceOf(com.storyboard.common.BusinessException.class);

        verify(repository).markPurgeBlocked("a1");
        verify(repository, never()).markPurgePending(anyString());
        verify(repository, never()).markPurged(anyString());
    }

    @Test
    void bulkPromoteMovesProjectAssetsToGlobalAndClearsFolderMappings() {
        when(repository.findAsset("u1", "a1")).thenReturn(Optional.of(asset("a1")));
        when(repository.findAsset("u1", "a2")).thenReturn(Optional.of(asset("a2")));
        when(repository.promote("u1", "a1", 1)).thenReturn(true);
        when(repository.promote("u1", "a2", 1)).thenReturn(true);

        var result = service.bulkPromote("u1", List.of("a1", "a2"));

        assertThat(result.updatedIds()).containsExactly("a1", "a2");
        verify(repository).removeAssetFolderMappings("u1", "a1");
        verify(repository).removeAssetFolderMappings("u1", "a2");
        verify(repository).unlinkAllProjects("a1");
        verify(repository).unlinkAllProjects("a2");
    }

    @Test
    void bulkTrashValidatesEveryAssetBeforeChangingAny() {
        when(repository.findAsset("u1", "a1")).thenReturn(Optional.of(asset("a1")));
        when(repository.findAsset("u1", "missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.bulkTrash("u1", List.of("a1", "missing")))
                .isInstanceOf(com.storyboard.common.BusinessException.class);

        verify(repository, never()).trash(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void duplicateCopiesAssetMediaTagsAndProjectLinks() {
        when(repository.findAsset("u1", "a1")).thenReturn(Optional.of(asset("a1")));
        when(repository.duplicateAsset(any(), anyString(), anyString())).thenReturn("copy1");

        var result = service.duplicate("u1", new DuplicateAssetsRequest(
                "u1", List.of("a1"), null));

        assertThat(result.updatedIds()).containsExactly("copy1");
        verify(repository).copyMediaLinks("a1", "copy1");
        verify(repository).copyAssetTags("a1", "copy1");
        verify(repository).copyProjectLinks("a1", "copy1", "u1");
    }

    @Test
    void getMediaRefreshesSignedUrlForCurrentUser() {
        MediaUrlSigner signer = mock(MediaUrlSigner.class);
        when(signer.sign(eq("m1"), eq("u1"), any())).thenReturn("123.fresh");
        AssetCatalogServiceImpl catalog = new AssetCatalogServiceImpl(
                repository, projectAccess, signer,
                new AssetPurgeService(repository),
                new StorageProperties(null, null, null, null, null, 0, 0, 0, 3, false, 6, 24));
        when(repository.findMedia("u1", "m1")).thenReturn(Optional.of(new MediaRef(
                "m1", "b1", MediaType.IMAGE, "images/a.png",
                "/api/library/media/m1/content", "images/a.png", "a.png", null,
                "image/png", 10, 1, 1, null, 0, MediaRole.REFERENCE, true, null)));

        MediaRef media = catalog.getMedia("u1", "m1");

        assertThat(media.contentUrl()).isEqualTo("/api/library/media/m1/content?token=123.fresh");
    }

    private static AssetSummary asset(String id) {
        return asset(id, com.storyboard.asset.client.AssetContracts.AssetStatus.ACTIVE);
    }

    private static AssetSummary asset(
            String id,
            com.storyboard.asset.client.AssetContracts.AssetStatus status
    ) {
        return new AssetSummary(
                id, "u1", AssetScope.PROJECT, "p1", AssetType.IMAGE, null,
                "demo", null, null, false,
                com.storyboard.asset.client.AssetContracts.MetadataState.NONE, status,
                null, 1, List.of(), List.of(), "upload", null,
                OffsetDateTime.now(), OffsetDateTime.now());
    }
}
