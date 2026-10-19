package com.storyboard.asset.service.impl;

import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.GenerationOutputEvent;
import com.storyboard.asset.client.AssetContracts.MediaType;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.service.AssetCatalogService;
import com.storyboard.asset.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationOutputServiceImplTest {

    @Test
    void registersDocumentOutputFromFilesPrefix() {
        LibraryRepository repository = mock(LibraryRepository.class);
        AssetCatalogService catalogService = mock(AssetCatalogService.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        GenerationOutputServiceImpl service =
                new GenerationOutputServiceImpl(repository, catalogService, storage);
        when(repository.findExistingOutput(any(), any(), anyInt())).thenReturn(Optional.empty());
        when(storage.contentLength(eq("files/report.md"))).thenReturn(64L);

        service.register(new GenerationOutputEvent(
                "moon-agent",
                "u1",
                "p1",
                "record-1",
                0,
                null,
                "record-1",
                "/api/files/files/report.md",
                "DOCUMENT",
                "生成报告",
                "model-x",
                Map.of()
        ));

        ArgumentCaptor<AssetCatalogService.MediaRegistration> captor =
                ArgumentCaptor.forClass(AssetCatalogService.MediaRegistration.class);
        verify(catalogService).registerMedia(eq("u1"), captor.capture(), anyString());
        assertThat(captor.getValue().assetType()).isEqualTo(AssetType.DOCUMENT);
        assertThat(captor.getValue().mediaType()).isEqualTo(MediaType.DOCUMENT);
        assertThat(captor.getValue().objectKey()).isEqualTo("files/report.md");
        assertThat(captor.getValue().mimeType()).isEqualTo("text/markdown");
        assertThat(captor.getValue().conversationId()).isNull();
    }

    @Test
    void keepsConversationIdFromMetadataSoOutputLandsInThatConversationFolder() {
        LibraryRepository repository = mock(LibraryRepository.class);
        AssetCatalogService catalogService = mock(AssetCatalogService.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        GenerationOutputServiceImpl service =
                new GenerationOutputServiceImpl(repository, catalogService, storage);
        when(repository.findExistingOutput(any(), any(), anyInt())).thenReturn(Optional.empty());
        when(storage.contentLength(eq("images/a.png"))).thenReturn(128L);

        service.register(new GenerationOutputEvent(
                "moon-agent",
                "u1",
                "p1",
                "record-1",
                0,
                null,
                "record-1",
                "/api/files/images/a.png",
                "IMAGE",
                "主视觉",
                "model-x",
                Map.of("conversationId", "conv-1", "sceneId", "scene-1")
        ));

        ArgumentCaptor<AssetCatalogService.MediaRegistration> captor =
                ArgumentCaptor.forClass(AssetCatalogService.MediaRegistration.class);
        verify(catalogService).registerMedia(eq("u1"), captor.capture(), anyString());
        assertThat(captor.getValue().conversationId()).isEqualTo("conv-1");
        assertThat(captor.getValue().sceneId()).isEqualTo("scene-1");
    }

    @Test
    void backfillsConversationOnAnExistingUnassignedOutput() {
        LibraryRepository repository = mock(LibraryRepository.class);
        AssetCatalogService catalogService = mock(AssetCatalogService.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        GenerationOutputServiceImpl service =
                new GenerationOutputServiceImpl(repository, catalogService, storage);
        when(repository.findExistingOutput(any(), any(), anyInt())).thenReturn(Optional.of("asset-1"));
        when(catalogService.get("u1", "asset-1"))
                .thenReturn(new com.storyboard.asset.client.AssetContracts.AssetDetail(
                        null, java.util.List.of(), java.util.List.of()));

        service.register(new GenerationOutputEvent(
                "moon-agent",
                "u1",
                "p1",
                "record-1",
                0,
                null,
                "record-1",
                "/api/files/images/a.png",
                "IMAGE",
                "主视觉",
                "model-x",
                Map.of("conversationId", "conv-1")
        ));

        verify(repository).attachOutputConversation("asset-1", "conv-1");
    }
}
