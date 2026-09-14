package com.storyboard.asset.service.impl;

import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.CreateAssetRequest;
import com.storyboard.asset.client.AssetContracts.MediaRole;
import com.storyboard.asset.client.AssetContracts.MediaType;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.security.MediaUrlSigner;
import com.storyboard.asset.service.AssetCatalogService;
import com.storyboard.asset.service.ProjectAccessPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
        service = new AssetCatalogServiceImpl(repository, projectAccess, mock(MediaUrlSigner.class));
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

    private static AssetSummary asset(String id) {
        return new AssetSummary(
                id, "u1", AssetScope.PROJECT, "p1", AssetType.IMAGE, null,
                "demo", null, null, false, com.storyboard.asset.client.AssetContracts.AssetStatus.ACTIVE,
                null, 1, List.of(), List.of(), "upload", null,
                OffsetDateTime.now(), OffsetDateTime.now());
    }
}
