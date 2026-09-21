package com.moon.moonagent.ai.impl;

import com.moon.moonagent.ai.AiConfigProperties;
import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.SceneGenerationBridge;
import com.moon.moonagent.service.FileStorageService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class VideoGenerationServiceImplReferenceTest {

    @Test
    void uploadsDataUriBeforeSendingVideoRequest() {
        VideoGenerationServiceImpl service = service();
        doReturn("mm_file://reference-image").when(service)
                .uploadReferenceToGateway(anyString(), any(byte[].class));

        String resolved = service.resolveReferenceUrl(
                "data:image/png;base64,AQID", "image");

        assertEquals("mm_file://reference-image", resolved);
        verify(service).uploadReferenceToGateway(
                anyString(), aryEq(new byte[]{1, 2, 3}));
    }

    @Test
    void uploadsApiFilesReferenceInsteadOfReturningInlineData() {
        VideoGenerationServiceImpl service = service();
        doReturn(new byte[]{9, 8, 7}).when(service).downloadBytes(anyString());
        doReturn("mm_file://api-file").when(service)
                .uploadReferenceToGateway(anyString(), any(byte[].class));
        String resolved = service.resolveReferenceUrl(
                "http://localhost/api/files/images/source.png", "image");

        assertEquals("mm_file://api-file", resolved);
    }

    @Test
    void uploadsSignedLibraryMediaReference() {
        VideoGenerationServiceImpl service = service();
        String signedUrl = "/api/library/media/media-1/content?token=1789980511.signature";
        doReturn(new byte[]{4, 5, 6})
                .when(service)
                .downloadBytes("http://localhost:8085" + signedUrl);
        doReturn("mm_file://library-file").when(service)
                .uploadReferenceToGateway(anyString(), any(byte[].class));

        String resolved = service.resolveReferenceUrl(signedUrl, "image");

        assertEquals("mm_file://library-file", resolved);
        verify(service).uploadReferenceToGateway(
                argThat(filename -> filename.endsWith(".png")),
                aryEq(new byte[]{4, 5, 6}));
    }

    private static VideoGenerationServiceImpl service() {
        return spy(new VideoGenerationServiceImpl(
                mock(AiConfigProperties.class),
                mock(GatewayModelService.class),
                mock(VideoPollExecutor.class),
                mock(FileStorageService.class),
                mock(SceneGenerationBridge.class)));
    }
}
