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
    }
}
