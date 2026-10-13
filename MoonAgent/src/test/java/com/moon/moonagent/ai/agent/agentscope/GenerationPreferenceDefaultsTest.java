package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.AgentGenerationPreferences;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationPreferenceDefaultsTest {

    private final GenerationPreferenceDefaults defaults = new GenerationPreferenceDefaults();

    private static AgentGenerationPreferences custom(Map<String, String> image, Map<String, String> video) {
        return AgentGenerationPreferences.of(false, false, image, video);
    }

    @Test
    void automaticModeLeavesInputUntouched() {
        Map<String, Object> input = Map.of("prompt", "一只猫");

        assertThat(defaults.applyDefaults("generate_image", input, AgentGenerationPreferences.none()))
                .isSameAs(input);
    }

    @Test
    void fillsMissingImageParamsAndKeepsGivenOnes() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("prompt", "一只猫");
        input.put("quality", "high");

        Map<String, Object> filled = defaults.applyDefaults("generate_image", input,
                custom(Map.of("model", "doubao-seedream-4.0", "size", "2K",
                        "quality", "low", "aspectRatio", "16:9"), Map.of()));

        assertThat(filled)
                .containsEntry("model", "doubao-seedream-4.0")
                .containsEntry("size", "2K")
                .containsEntry("aspect_ratio", "16:9")
                .containsEntry("quality", "high");
        assertThat(input).doesNotContainKey("size");
    }

    @Test
    void mapsVideoPreferencesOntoVideoAndStoryboardTools() {
        AgentGenerationPreferences preferences = custom(Map.of(), Map.of(
                "model", "doubao-seedance-1.0", "resolution", "1080P", "aspectRatio", "9:16"));

        assertThat(defaults.applyDefaults("generate_video", Map.of("prompt", "镜头"), preferences))
                .containsEntry("model", "doubao-seedance-1.0")
                .containsEntry("resolution", "1080P")
                .containsEntry("aspect_ratio", "9:16");
        assertThat(defaults.applyDefaults("generate_storyboard_videos",
                        Map.of("run_id", "run-1"), preferences))
                .containsEntry("resolution", "1080P")
                .containsEntry("aspect_ratio", "9:16");
        assertThat(defaults.applyDefaults("generate_storyboard_video",
                        Map.of("run_id", "run-1", "model", "doubao-seedance-1.0-pro"), preferences))
                .containsEntry("model", "doubao-seedance-1.0-pro")
                .containsEntry("resolution", "1080P");
    }

    @Test
    void fillsBatchImageFollowUpVideoKeys() {
        AgentGenerationPreferences preferences = custom(
                Map.of("size", "2K"), Map.of("aspectRatio", "16:9", "resolution", "720P"));

        assertThat(defaults.applyDefaults("generate_image_batch",
                        Map.of("prompt", "分镜图"), preferences))
                .containsEntry("size", "2K")
                .containsEntry("video_aspect_ratio", "16:9")
                .containsEntry("video_resolution", "720P");
    }

    @Test
    void ignoresUnrelatedToolsAndEmptyPreferences() {
        assertThat(defaults.applyDefaults("write_file", Map.of("path", "a.txt"),
                custom(Map.of("size", "2K"), Map.of())))
                .containsExactly(Map.entry("path", "a.txt"));
        assertThat(defaults.applyDefaults("generate_image", Map.of("prompt", "x"),
                custom(Map.of(), Map.of())))
                .containsExactly(Map.entry("prompt", "x"));
    }
}
