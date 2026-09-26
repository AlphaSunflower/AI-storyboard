package com.moon.moonagent.ai.agent.storyboard;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VideoGenerationPolicyTest {

    @Test
    void fifteenSecondsCanStillBeGeneratedAsOneVideo() {
        assertThat(VideoGenerationPolicy.requiresStoryboard("15", null)).isFalse();
        assertThat(VideoGenerationPolicy.requiresStoryboard("15秒", null)).isFalse();
    }

    @Test
    void durationOverFifteenSecondsRequiresStoryboard() {
        assertThat(VideoGenerationPolicy.requiresStoryboard("16", null)).isTrue();
        assertThat(VideoGenerationPolicy.requiresStoryboard("120", null)).isTrue();
        assertThat(VideoGenerationPolicy.requiresStoryboard("2分钟", null)).isTrue();
    }

    @Test
    void originalRequestTotalDurationAlsoTriggersStoryboardRoute() {
        assertThat(VideoGenerationPolicy.requiresStoryboard(
                "8", "给我生成一个两分钟的三层别墅视频")).isTrue();
    }

    @Test
    void mixedTotalAndPerShotDurationsStillUseTheLongestExplicitTotal() {
        assertThat(VideoGenerationPolicy.requiresStoryboard(
                "8", "生成两分钟视频，每镜 8 秒")).isTrue();
    }

    @Test
    void halfMinutePhrasesAlsoRequireStoryboard() {
        assertThat(VideoGenerationPolicy.requiresStoryboard(null, "生成一分半的视频")).isTrue();
        assertThat(VideoGenerationPolicy.requiresStoryboard(null, "生成两分半的视频")).isTrue();
    }
}
