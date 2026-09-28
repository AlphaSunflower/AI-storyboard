package com.storyboard.asset.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectKeysTest {

    @Test
    void parsesApiFilesUrlWithQuery() {
        assertThat(ObjectKeys.fromUrl("/api/files/images/a.png?token=1"))
                .isEqualTo("images/a.png");
    }

    @Test
    void parsesBarePrefixUrl() {
        assertThat(ObjectKeys.fromUrl("videos/b.mp4")).isEqualTo("videos/b.mp4");
        assertThat(ObjectKeys.fromUrl("audios/c.mp3")).isEqualTo("audios/c.mp3");
        assertThat(ObjectKeys.fromUrl("files/d.pdf")).isEqualTo("files/d.pdf");
    }

    @Test
    void parsesAbsoluteApiFilesUrl() {
        assertThat(ObjectKeys.fromUrl("http://localhost:8080/api/files/images/e.jpg"))
                .isEqualTo("images/e.jpg");
    }

    @Test
    void returnsNullForUnresolvableUrl() {
        assertThat(ObjectKeys.fromUrl(null)).isNull();
        assertThat(ObjectKeys.fromUrl("")).isNull();
        assertThat(ObjectKeys.fromUrl("data:image/png;base64,AAAA")).isNull();
        assertThat(ObjectKeys.fromUrl("https://example.com/pic.png")).isNull();
        assertThat(ObjectKeys.fromUrl("/uploads/local.png")).isNull();
    }

    @Test
    void parsesJsonArrayOfUrls() {
        assertThat(ObjectKeys.fromJsonArray("[\"/api/files/images/a.png\",\"videos/b.mp4\"]"))
                .containsExactlyInAnyOrder("images/a.png", "videos/b.mp4");
    }

    @Test
    void ignoresUnresolvableEntriesInJsonArray() {
        assertThat(ObjectKeys.fromJsonArray("[\"https://example.com/x.png\",\"images/ok.png\"]"))
                .containsExactly("images/ok.png");
        assertThat(ObjectKeys.fromJsonArray("not-json")).isEmpty();
        assertThat(ObjectKeys.fromJsonArray(null)).isEmpty();
    }
}