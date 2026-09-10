package com.moon.moonagent.ai.agent.handler;

import com.moon.moonagent.entity.Scene;
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

    @Test
    void parseSceneImageParams_readsIndependentOptionsPerScene() {
        Map<String, SceneReviewIntentHandler.SceneImageRequestParams> parsed =
                SceneReviewIntentHandler.parseSceneImageParams("""
                        {
                          "scene-1":{"model":"doubao-seedream-4-0-250828","size":"1K","quality":"high","n":2},
                          "scene-2":{"model":"doubao-seedream-4-0-250828","size":"2K","n":3}
                        }
                        """);

        assertThat(parsed).containsKeys("scene-1", "scene-2");
        assertThat(parsed.get("scene-1").n()).isEqualTo(2);
        assertThat(parsed.get("scene-2").size()).isEqualTo("2K");
        assertThat(parsed.get("scene-2").n()).isEqualTo(3);
    }

    @Test
    void shouldFallbackImageModel_onlyForAvailabilityErrors() {
        assertThat(SceneReviewIntentHandler.shouldFallbackImageModel(
                new RuntimeException("Image Edit API returned 403: insufficient_user_quota"))).isTrue();
        assertThat(SceneReviewIntentHandler.shouldFallbackImageModel(
                new RuntimeException("no route for model: old-model"))).isTrue();
        assertThat(SceneReviewIntentHandler.shouldFallbackImageModel(
                new RuntimeException("upstream request timeout"))).isFalse();
        assertThat(SceneReviewIntentHandler.shouldFallbackImageModel(
                new RuntimeException("OutputImageSensitiveContentDetected"))).isFalse();
    }

    @Test
    void imageGenerationBatch_explicitSelectionIncludesExistingImages() {
        Scene existing = new Scene();
        existing.setId("scene-existing");
        existing.setImageUrl("/api/files/images/existing.jpeg");
        Scene missing = new Scene();
        missing.setId("scene-missing");

        assertThat(SceneReviewIntentHandler.imageGenerationBatch(
                List.of(existing), true)).containsExactly(existing);
        assertThat(SceneReviewIntentHandler.imageGenerationBatch(
                List.of(existing, missing), false)).containsExactly(missing);
    }
}
