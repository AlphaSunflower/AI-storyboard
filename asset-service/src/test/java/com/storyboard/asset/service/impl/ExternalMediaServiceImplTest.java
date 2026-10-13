package com.storyboard.asset.service.impl;

import com.storyboard.asset.client.AssetContracts.AssetDetail;
import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetStatus;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.ExternalMediaRegistration;
import com.storyboard.asset.client.AssetContracts.MediaRole;
import com.storyboard.asset.client.AssetContracts.MediaType;
import com.storyboard.asset.client.AssetContracts.MetadataState;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.service.AssetCatalogService;
import com.storyboard.asset.storage.ObjectStorage;
import com.storyboard.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalMediaServiceImplTest {

    private LibraryRepository repository;
    private AssetCatalogService catalogService;
    private ObjectStorage storage;
    private ExternalMediaServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(LibraryRepository.class);
        catalogService = mock(AssetCatalogService.class);
        storage = mock(ObjectStorage.class);
        service = new ExternalMediaServiceImpl(repository, catalogService, storage);
    }

    @Test
    void reusesAssetAlreadyRegisteredForSource() {
        AssetSummary existing = asset("asset-1");
        when(repository.findAssetBySource("user-1", "canvas", "canvas-asset-1"))
                .thenReturn(Optional.of(existing));
        when(catalogService.get("user-1", "asset-1"))
                .thenReturn(new AssetDetail(existing, List.of(), List.of()));

        AssetSummary result = service.register("user-1", new ExternalMediaRegistration(
                "user-1", "project-1", "IMAGE", "frame.png", null,
                "canvas", "canvas-asset-1", "images/frame.png",
                "image/png", 12L, null, MediaRole.REFERENCE, 0));

        assertThat(result.id()).isEqualTo("asset-1");
        verify(catalogService, never()).registerMedia(any(), any(), any());
    }

    @Test
    void registersExternalObjectWhenSourceIsNew() {
        when(repository.findAssetBySource("user-1", "canvas", "canvas-asset-2"))
                .thenReturn(Optional.empty());
        when(storage.exists("images/frame.png")).thenReturn(true);
        when(storage.contentLength("images/frame.png")).thenReturn(12L);
        AssetSummary registered = asset("asset-2");
        when(catalogService.registerMedia(eq("user-1"), any(), eq("external:canvas:canvas-asset-2")))
                .thenReturn(registered);

        AssetSummary result = service.register("user-1", new ExternalMediaRegistration(
                "user-1", "project-1", "IMAGE", "frame.png", null,
                "canvas", "canvas-asset-2", "images/frame.png",
                "image/png", null, null, MediaRole.REFERENCE, 0));

        assertThat(result.id()).isEqualTo("asset-2");
        verify(catalogService).registerMedia(
                eq("user-1"),
                org.mockito.ArgumentMatchers.argThat(registration ->
                        registration.scope() == AssetScope.PROJECT
                                && registration.projectId().equals("project-1")
                                && registration.assetType() == AssetType.IMAGE
                                && registration.mediaType() == MediaType.IMAGE
                                && registration.objectKey().equals("images/frame.png")),
                eq("external:canvas:canvas-asset-2"));
    }

    @Test
    void stripsSignatureQueryFromObjectKey() {
        when(repository.findAssetBySource("user-1", "canvas-upload", "canvas-asset-3"))
                .thenReturn(Optional.empty());
        when(storage.exists("images/frame.png")).thenReturn(true);
        when(storage.contentLength("images/frame.png")).thenReturn(12L);
        when(catalogService.registerMedia(eq("user-1"), any(), any())).thenReturn(asset("asset-3"));

        service.register("user-1", new ExternalMediaRegistration(
                "user-1", "project-1", "IMAGE", "frame.png", null,
                "canvas-upload", "canvas-asset-3", "/api/files/images/frame.png?token=1.abc",
                "image/png", null, null, MediaRole.REFERENCE, 0));

        verify(catalogService).registerMedia(
                eq("user-1"),
                org.mockito.ArgumentMatchers.argThat(registration ->
                        registration.objectKey().equals("images/frame.png")
                                && registration.sha256().equals(
                                        LibraryRepository.sha256Hex(
                                                "images/frame.png".getBytes(StandardCharsets.UTF_8)))),
                eq("external:canvas-upload:canvas-asset-3"));
    }

    @Test
    void rejectsObjectMissingFromStorage() {
        when(repository.findAssetBySource("user-1", "canvas", "canvas-asset-4"))
                .thenReturn(Optional.empty());
        when(storage.exists("images/gone.png")).thenReturn(false);

        assertThatThrownBy(() -> service.register("user-1", new ExternalMediaRegistration(
                "user-1", null, "IMAGE", "gone.png", null,
                "canvas", "canvas-asset-4", "images/gone.png",
                "image/png", null, null, MediaRole.REFERENCE, 0)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("外部媒体对象不存在");
    }

    private static AssetSummary asset(String id) {
        return new AssetSummary(
                id, "user-1", AssetScope.PROJECT, "project-1", AssetType.IMAGE, null,
                "frame.png", null, null, false, MetadataState.NONE, AssetStatus.ACTIVE,
                "/api/files/images/frame.png", 1L, List.of(), List.of(), null, List.of(),
                "canvas", "canvas-asset-1", OffsetDateTime.now(), OffsetDateTime.now());
    }
}
