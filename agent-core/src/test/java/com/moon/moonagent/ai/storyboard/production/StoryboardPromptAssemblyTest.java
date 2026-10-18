package com.moon.moonagent.ai.storyboard.production;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 衔接说明的合并规则：只有"送进模型前那一刻"才拼，且不得重复堆叠。
 */
class StoryboardPromptAssemblyTest {

    @Test
    void appendsContinuityWithAVisibleMarker() {
        String prompt = StoryboardPromptAssembly.withContinuity("人物推门进入仓库", "承接雨夜冷蓝侧逆光；湿发不得回退");

        assertThat(prompt)
                .startsWith("人物推门进入仓库")
                .contains("[衔接]")
                .contains("湿发不得回退");
    }

    @Test
    void keepsPromptUntouchedWhenNoContinuity() {
        assertThat(StoryboardPromptAssembly.withContinuity("人物推门进入仓库", ""))
                .isEqualTo("人物推门进入仓库");
        assertThat(StoryboardPromptAssembly.withContinuity("人物推门进入仓库", null))
                .isEqualTo("人物推门进入仓库");
        assertThat(StoryboardPromptAssembly.withContinuity(null, "承接上一镜光位"))
                .isEqualTo("承接上一镜光位");
        assertThat(StoryboardPromptAssembly.withContinuity(null, null)).isEmpty();
    }

    @Test
    void doesNotStackContinuityTwice() {
        String once = StoryboardPromptAssembly.withContinuity("画面", "承接上一镜光位");
        String twice = StoryboardPromptAssembly.withContinuity(once, "承接上一镜光位");

        assertThat(twice).isEqualTo(once);
        assertThat(twice.split("\\[衔接\\]", -1)).hasSize(2);
    }
}
