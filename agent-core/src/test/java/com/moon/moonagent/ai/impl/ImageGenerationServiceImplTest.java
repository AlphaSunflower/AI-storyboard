package com.moon.moonagent.ai.impl;

import com.moon.moonagent.ai.AiConfigProperties;
import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.SceneGenerationBridge;
import com.moon.moonagent.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.image.ImageModel;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

class ImageGenerationServiceImplTest {

    private static final String FALLBACK = "1024x1024";
    private static final List<String> VALID_SIZES = List.of("1024x1024", "1536x1024", "1024x1536");

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

    @Test
    void normalizesImageSizeAgainstConfiguredWhitelist() {
        assertEquals("1024x1024",
                ImageGenerationServiceImpl.normalizeImageSize("1024x1024", FALLBACK, VALID_SIZES));
        assertEquals("1536x1024",
                ImageGenerationServiceImpl.normalizeImageSize("1536x1024 / 1024x1024", FALLBACK, VALID_SIZES));
        assertEquals("1024x1536",
                ImageGenerationServiceImpl.normalizeImageSize("1024x1536、1792x1024", FALLBACK, VALID_SIZES));
        assertEquals(FALLBACK,
                ImageGenerationServiceImpl.normalizeImageSize("2K / 4K", FALLBACK, VALID_SIZES));
        assertEquals(FALLBACK,
                ImageGenerationServiceImpl.normalizeImageSize(null, FALLBACK, VALID_SIZES));
    }

    @Test
    void cleansBase64DataUriAndPadding() {
        assertFalse(ImageGenerationServiceImpl.cleanBase64("data:image/png;base64,iVBORw0KGgo=")
                .startsWith("data:"));
        assertEquals(0, ImageGenerationServiceImpl.cleanBase64("abc").length() % 4);
        assertEquals("", ImageGenerationServiceImpl.cleanBase64(null));
    }
}
