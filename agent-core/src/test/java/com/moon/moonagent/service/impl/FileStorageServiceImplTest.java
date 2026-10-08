package com.moon.moonagent.service.impl;

import com.moon.moonagent.security.FileUrlSigner;
import com.moon.moonagent.storage.MinioStorage;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FileStorageServiceImplTest {

    private final FileStorageServiceImpl service = new FileStorageServiceImpl(
            mock(MinioStorage.class),
            new FileUrlSigner("test-internal-secret", 900));

    @Test
    void generatedAndUploadedUrlsCarryFileSignatures() {
        String base64 = Base64.getEncoder().encodeToString("image".getBytes(StandardCharsets.UTF_8));
        String generated = service.saveImageFromBase64("data:image/png;base64," + base64);
        String uploaded = service.saveUploadedImage(
                new MockMultipartFile("file", "a.png", "image/png", new byte[]{1, 2, 3}));

        assertThat(generated).startsWith("/api/files/images/").contains("?token=");
        assertThat(uploaded).startsWith("/api/files/images/").contains("?token=");
    }
}
