package com.moon.moonagent.ai.impl;

import com.moon.moonagent.ai.AiConfigProperties;
import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.SceneGenerationBridge;
import com.moon.moonagent.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.image.ImageModel;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

class ImageGenerationServiceImplTest {

    @Test
    void includesMaskAndSelectedEditFieldsInMultipart() throws Exception {
        byte[] body = ImageGenerationServiceImpl.buildImageEditMultipart(
                "gpt-image-1",
                "change the chair",
                "source.png",
                "image/png",
                new byte[]{1, 2, 3},
                new byte[]{4, 5, 6},
                "1024x1024",
                "high",
                2,
                Map.of("style", "natural", "ignoredObject", Map.of("nested", true))
        );
        String multipart = new String(body, StandardCharsets.ISO_8859_1);

        assertTrue(multipart.contains("name=\"model\""));
        assertTrue(multipart.contains("name=\"prompt\""));
        assertTrue(multipart.contains("name=\"image\"; filename=\"source.png\""));
        assertTrue(multipart.contains("name=\"mask\"; filename=\"redraw-mask.png\""));
        assertTrue(multipart.contains("name=\"size\""));
        assertTrue(multipart.contains("name=\"quality\""));
        assertTrue(multipart.contains("name=\"n\""));
        assertTrue(multipart.contains("name=\"style\""));
    }

    @Test
    void convertsLibraryMediaReferenceToDataUriForSeedream() throws Exception {
        ImageGenerationServiceImpl service = spy(new ImageGenerationServiceImpl(
                mock(AiConfigProperties.class),
                mock(GatewayModelService.class),
                mock(FileStorageService.class),
                mock(SceneGenerationBridge.class),
                mock(ImageModel.class)));
        String signedUrl = "/api/library/media/media-1/content?token=signed";
        doReturn(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10})
                .when(service).fetchAssetServiceImage(signedUrl);

        String resolved = service.resolveSeedreamReference(signedUrl);

        assertThat(resolved).startsWith("data:image/png;base64,");
        assertThat(resolved).doesNotContain("/api/library/media/");
    }
}
