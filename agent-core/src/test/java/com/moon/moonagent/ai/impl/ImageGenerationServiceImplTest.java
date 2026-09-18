package com.moon.moonagent.ai.impl;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
