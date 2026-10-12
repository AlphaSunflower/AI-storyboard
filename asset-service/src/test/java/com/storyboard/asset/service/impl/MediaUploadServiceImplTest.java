package com.storyboard.asset.service.impl;

import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.MediaType;
import com.storyboard.asset.config.StorageProperties;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.service.AssetCatalogService;
import com.storyboard.asset.service.ProjectAccessPort;
import com.storyboard.asset.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaUploadServiceImplTest {

    private AssetCatalogService catalogService;
    private ObjectStorage storage;
    private LibraryRepository repository;
    private MediaUploadServiceImpl service;

    @BeforeEach
    void setUp() {
        catalogService = mock(AssetCatalogService.class);
        storage = mock(ObjectStorage.class);
        repository = mock(LibraryRepository.class);
        service = new MediaUploadServiceImpl(
                catalogService,
                mock(ProjectAccessPort.class),
                storage,
                mock(StorageProperties.class),
                repository
        );
    }

    @Test
    void directUploadReusesExistingBlobObjectKey() throws Exception {
        byte[] bytes = pngBytes();
        when(storage.objectKey("IMAGE", "png")).thenReturn("images/new.png");
        when(repository.findBlobBySha(anyString())).thenReturn(Optional.of(blobRow("images/original.png")));
        when(storage.exists("images/original.png")).thenReturn(true);

        service.uploadDirect(
                "u1", null, AssetScope.GLOBAL, "photo", null, null,
                new MockMultipartFile("file", "photo.png", "image/png", bytes), "idem-1"
        );

        ArgumentCaptor<AssetCatalogService.MediaRegistration> registration =
                ArgumentCaptor.forClass(AssetCatalogService.MediaRegistration.class);
        verify(catalogService).registerMedia(eq("u1"), registration.capture(), eq("idem-1"));
        assertThat(registration.getValue().objectKey()).isEqualTo("images/original.png");
        verify(storage, never()).put(anyString(), any(), anyLong(), anyString());
    }

    @Test
    void directUploadRehydratesMissingDeduplicatedObject() throws Exception {
        byte[] bytes = pngBytes();
        when(storage.objectKey("IMAGE", "png")).thenReturn("images/new.png");
        when(repository.findBlobBySha(anyString())).thenReturn(Optional.of(blobRow("images/original.png")));
        when(storage.exists("images/original.png")).thenReturn(false);

        service.uploadDirect(
                "u1", null, AssetScope.GLOBAL, "photo", null, null,
                new MockMultipartFile("file", "photo.png", "image/png", bytes), "idem-2"
        );

        verify(storage).put(
                eq("images/original.png"),
                any(ByteArrayInputStream.class),
                eq((long) bytes.length),
                eq("image/png")
        );
    }

    @Test
    void directUploadStoresDocumentWithoutAnalysisTask() throws Exception {
        byte[] bytes = "# 说明".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(storage.objectKey("DOCUMENT", "md")).thenReturn("documents/new.md");
        when(repository.findBlobBySha(anyString())).thenReturn(Optional.empty());

        service.uploadDirect(
                "u1", null, AssetScope.GLOBAL, "说明", null, null,
                new MockMultipartFile("file", "说明.md", "text/markdown", bytes), "idem-doc"
        );

        ArgumentCaptor<AssetCatalogService.MediaRegistration> registration =
                ArgumentCaptor.forClass(AssetCatalogService.MediaRegistration.class);
        verify(catalogService).registerMedia(eq("u1"), registration.capture(), eq("idem-doc"));
        assertThat(registration.getValue().assetType()).isEqualTo(AssetType.DOCUMENT);
        assertThat(registration.getValue().mediaType()).isEqualTo(MediaType.DOCUMENT);
        assertThat(registration.getValue().objectKey()).isEqualTo("documents/new.md");
        assertThat(registration.getValue().mimeType()).isEqualTo("text/markdown");
    }

    @Test
    void directUploadStoresUnknownFileAsOther() throws Exception {
        byte[] bytes = "PK\u0003\u0004zip".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        when(storage.objectKey("OTHER", "zip")).thenReturn("files/new.zip");
        when(repository.findBlobBySha(anyString())).thenReturn(Optional.empty());

        service.uploadDirect(
                "u1", null, AssetScope.GLOBAL, "bundle", null, null,
                new MockMultipartFile("file", "bundle.zip", "application/zip", bytes), "idem-zip"
        );

        ArgumentCaptor<AssetCatalogService.MediaRegistration> registration =
                ArgumentCaptor.forClass(AssetCatalogService.MediaRegistration.class);
        verify(catalogService).registerMedia(eq("u1"), registration.capture(), eq("idem-zip"));
        assertThat(registration.getValue().assetType()).isEqualTo(AssetType.OTHER);
        assertThat(registration.getValue().mediaType()).isEqualTo(MediaType.OTHER);
        assertThat(registration.getValue().objectKey()).isEqualTo("files/new.zip");
    }

    private static LibraryRepository.BlobRow blobRow(String objectKey) {
        return new LibraryRepository.BlobRow(
                "blob-1",
                objectKey,
                "sha",
                "image/png",
                12,
                null,
                null,
                null,
                MediaType.IMAGE
        );
    }

    private static byte[] pngBytes() {
        return new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                0x00, 0x00, 0x00, 0x00
        };
    }
}
