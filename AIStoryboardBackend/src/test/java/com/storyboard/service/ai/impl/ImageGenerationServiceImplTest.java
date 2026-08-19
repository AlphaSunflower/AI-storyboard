package com.storyboard.service.ai.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImageGenerationServiceImplTest {

    private static final String FALLBACK = "1024x1024";

    // ===== normalizeImageSize =====

    @Test
    void validSize_passesThrough() {
        assertEquals("1024x1024", ImageGenerationServiceImpl.normalizeImageSize("1024x1024", FALLBACK));
        assertEquals("1536x1024", ImageGenerationServiceImpl.normalizeImageSize("1536x1024", FALLBACK));
        assertEquals("1024x1536", ImageGenerationServiceImpl.normalizeImageSize("1024x1536", FALLBACK));
    }

    @Test
    void nullOrBlank_returnsFallback() {
        assertEquals(FALLBACK, ImageGenerationServiceImpl.normalizeImageSize(null, FALLBACK));
        assertEquals(FALLBACK, ImageGenerationServiceImpl.normalizeImageSize("", FALLBACK));
        assertEquals(FALLBACK, ImageGenerationServiceImpl.normalizeImageSize("   ", FALLBACK));
    }

    @Test
    void invalidSize_returnsFallback() {
        assertEquals(FALLBACK, ImageGenerationServiceImpl.normalizeImageSize("2K", FALLBACK));
        assertEquals(FALLBACK, ImageGenerationServiceImpl.normalizeImageSize("4K", FALLBACK));
        assertEquals(FALLBACK, ImageGenerationServiceImpl.normalizeImageSize("1792x1024", FALLBACK));
        assertEquals(FALLBACK, ImageGenerationServiceImpl.normalizeImageSize("512x512", FALLBACK));
    }

    @Test
    void multiValueList_picksFirstValid() {
        assertEquals("1536x1024",
                ImageGenerationServiceImpl.normalizeImageSize("1536x1024 / 1024x1024 / 1024x1536", FALLBACK));
        assertEquals("1024x1024",
                ImageGenerationServiceImpl.normalizeImageSize("2K|1024x1024|1536x1024", FALLBACK));
    }

    @Test
    void multiValueList_allInvalid_returnsFallback() {
        assertEquals(FALLBACK,
                ImageGenerationServiceImpl.normalizeImageSize("2K / 4K / 1792x1024", FALLBACK));
    }

    @Test
    void chineseSeparators_handled() {
        assertEquals("1024x1024",
                ImageGenerationServiceImpl.normalizeImageSize("1024x1024，1536x1024", FALLBACK));
        assertEquals("1536x1024",
                ImageGenerationServiceImpl.normalizeImageSize("1536x1024、1024x1024", FALLBACK));
    }

    // ===== cleanBase64 =====

    @Test
    void cleanBase64_stripsDataUriPrefix() {
        String input = "data:image/png;base64,iVBORw0KGgo=";
        String result = ImageGenerationServiceImpl.cleanBase64(input);
        assertFalse(result.startsWith("data:"));
        assertTrue(result.endsWith("="));
    }

    @Test
    void cleanBase64_paddsToMultipleOf4() {
        String input = "abc"; // length 3, needs 1 padding
        String result = ImageGenerationServiceImpl.cleanBase64(input);
        assertEquals(0, result.length() % 4);
    }

    @Test
    void cleanBase64_nullReturnsEmpty() {
        assertEquals("", ImageGenerationServiceImpl.cleanBase64(null));
    }
}
