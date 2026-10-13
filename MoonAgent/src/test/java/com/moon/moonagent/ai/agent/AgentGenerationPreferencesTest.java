package com.moon.moonagent.ai.agent;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentGenerationPreferencesTest {

    @Test
    void noneMeansAutomaticAndNoPlanExemption() {
        AgentGenerationPreferences none = AgentGenerationPreferences.none();

        assertThat(none.auto()).isTrue();
        assertThat(none.ignoreGenerationPlan()).isFalse();
        assertThat(none.hasMediaOverrides()).isFalse();
        assertThat(none.hasPromptHints()).isFalse();
    }

    @Test
    void automaticWithIgnorePlanOnlyHintsThePlanExemption() {
        AgentGenerationPreferences preferences =
                AgentGenerationPreferences.of(true, true, Map.of(), Map.of());

        assertThat(preferences.hasMediaOverrides()).isFalse();
        assertThat(preferences.hasPromptHints()).isTrue();
    }

    @Test
    void dropsBlankValuesAndTrimsKeys() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put(" size ", " 2K ");
        raw.put("quality", "   ");
        raw.put("", "16:9");

        AgentGenerationPreferences preferences =
                AgentGenerationPreferences.of(false, false, raw, Map.of());

        assertThat(preferences.image()).containsExactly(Map.entry("size", "2K"));
        assertThat(preferences.hasMediaOverrides()).isTrue();
    }

    @Test
    void routesToolsToTheMatchingMediaGroup() {
        AgentGenerationPreferences preferences = AgentGenerationPreferences.of(false, false,
                Map.of("size", "2K"), Map.of("resolution", "1080P"));

        assertThat(preferences.preferenceFor("generate_image")).containsEntry("size", "2K");
        assertThat(preferences.preferenceFor("generate_storyboard_images")).containsEntry("size", "2K");
        assertThat(preferences.preferenceFor("generate_video")).containsEntry("resolution", "1080P");
        assertThat(preferences.preferenceFor("generate_storyboard_videos"))
                .containsEntry("resolution", "1080P");
        assertThat(preferences.preferenceFor("write_file")).isEmpty();
    }

    @Test
    void mediaGenerationToolsCoverImageVideoAndStoryboard() {
        assertThat(AgentGenerationPreferences.isMediaGenerationTool("generate_image")).isTrue();
        assertThat(AgentGenerationPreferences.isMediaGenerationTool("generate_image_batch")).isTrue();
        assertThat(AgentGenerationPreferences.isMediaGenerationTool("generate_video")).isTrue();
        assertThat(AgentGenerationPreferences.isMediaGenerationTool("generate_storyboard_image")).isTrue();
        assertThat(AgentGenerationPreferences.isMediaGenerationTool("generate_storyboard_images")).isTrue();
        assertThat(AgentGenerationPreferences.isMediaGenerationTool("generate_storyboard_video")).isTrue();
        assertThat(AgentGenerationPreferences.isMediaGenerationTool("generate_storyboard_videos")).isTrue();
        assertThat(AgentGenerationPreferences.isMediaGenerationTool("write_file")).isFalse();
        assertThat(AgentGenerationPreferences.isMediaGenerationTool(null)).isFalse();
    }

    @Test
    void describesPreferencesInReadableLabels() {
        AgentGenerationPreferences preferences = AgentGenerationPreferences.none();

        assertThat(preferences.describe(Map.of("model", "doubao-seedream-4.0")))
                .isEqualTo("model=doubao-seedream-4.0");
        assertThat(preferences.describe(Map.of()))
                .isEmpty();
    }
}
