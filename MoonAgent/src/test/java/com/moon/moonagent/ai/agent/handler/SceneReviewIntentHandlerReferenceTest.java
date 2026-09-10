package com.moon.moonagent.ai.agent.handler;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SceneReviewIntentHandlerReferenceTest {

    @Test
    void mergeImageReferences_keepsImagesDropsVideos_andDeduplicates() {
        List<Map<String, Object>> refs = List.of(
                Map.of("type", "image", "url", "/api/files/images/ref.png"),
                Map.of("type", "video", "url", "/api/files/videos/ref.mp4"),
                Map.of("type", "image", "url", "/api/files/images/asset.png"));

        List<String> merged = SceneReviewIntentHandler.mergeImageReferences(
                refs, List.of("/api/files/images/asset.png", "/api/files/images/a2.png"));

        assertThat(merged).containsExactly(
                "/api/files/images/ref.png",
                "/api/files/images/asset.png",
                "/api/files/images/a2.png");
    }
}
