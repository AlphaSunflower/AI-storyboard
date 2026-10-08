package com.moon.moonagent.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileUrlSignerTest {

    private final FileUrlSigner signer = new FileUrlSigner("test-internal-secret", 900);

    @Test
    void signsLocalPathsOnly() {
        assertThat(signer.signIfLocal("/api/files/images/a.png"))
                .startsWith("/api/files/images/a.png?token=");
        assertThat(signer.signIfLocal("https://cdn.example.com/a.png"))
                .isEqualTo("https://cdn.example.com/a.png");
    }

    @Test
    void signsBothResultUrlShapes() {
        String strings = signer.signJsonArray("[\"/api/files/images/a.png\"]");
        String objects = signer.signJsonArray("[{\"url\":\"/api/files/videos/v.mp4\",\"role\":\"source\"}]");

        assertThat(strings).contains("/api/files/images/a.png?token=");
        assertThat(objects).contains("/api/files/videos/v.mp4?token=").contains("\"role\":\"source\"");
    }

    @Test
    void signsFileUrlsEmbeddedInMarkdownAndMetadata() {
        String markdown = "![生成图片](/api/files/images/a.png)\n\n/api/files/videos/v.mp4";
        assertThat(signer.signEmbeddedUrls(markdown))
                .contains("/api/files/images/a.png?token=")
                .contains("/api/files/videos/v.mp4?token=");

        Map<String, Object> signed = signer.signMetadata(Map.of(
                "messageInput", Map.of(
                        "picUrls", List.of("/api/files/images/a.png")),
                "external", "https://cdn.example.com/a.png"));
        assertThat(signed.toString())
                .contains("/api/files/images/a.png?token=")
                .contains("https://cdn.example.com/a.png");
    }
}
