package com.moon.moonagent.ai.agent.storyboard;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SceneDurationPlannerTest {

    @Test
    void allocate_thirtyScenesForFiveMinutes_returnsTenSecondsEach() {
        SceneDurationPlanner.Allocation allocation =
                SceneDurationPlanner.allocate(300, Collections.nCopies(30, "等长分镜内容"));

        assertThat(allocation.feasible()).isTrue();
        assertThat(allocation.durations()).hasSize(30).containsOnly(10);
        assertThat(allocation.durations().stream().mapToInt(Integer::intValue).sum()).isEqualTo(300);
    }

    @Test
    void allocate_rejectsTotalBelowMinimum() {
        SceneDurationPlanner.Allocation allocation =
                SceneDurationPlanner.allocate(100, Collections.nCopies(30, "内容"));

        assertThat(allocation.feasible()).isFalse();
        assertThat(allocation.durations()).isEmpty();
        assertThat(allocation.message()).contains("100 秒").contains("120~450 秒");
    }

    @Test
    void parseTotalSeconds_supportsChineseAndCompoundDurations() {
        assertThat(SceneDurationPlanner.parseTotalSeconds("请生成五分钟的视频"))
                .isEqualTo(300);
        assertThat(SceneDurationPlanner.parseTotalSeconds("总时长 5分30秒"))
                .isEqualTo(330);
        assertThat(SceneDurationPlanner.parseTotalSeconds("目标 300 秒"))
                .isEqualTo(300);
    }

    @Test
    void parseTotalSeconds_ignoresPerShotDuration() {
        assertThat(SceneDurationPlanner.parseTotalSeconds("每个镜头10秒"))
                .isNull();
        assertThat(SceneDurationPlanner.parseTotalSeconds("单镜 8 秒"))
                .isNull();
    }

    @Test
    void parseTotalSeconds_usesLongestExplicitTotalWhenMixedWithPerShotDuration() {
        assertThat(SceneDurationPlanner.parseTotalSeconds("生成两分钟视频，每镜 8 秒"))
                .isEqualTo(120);
    }

    @Test
    void parseTotalSeconds_supportsHalfMinutePhrases() {
        assertThat(SceneDurationPlanner.parseTotalSeconds("生成一分半的视频"))
                .isEqualTo(90);
        assertThat(SceneDurationPlanner.parseTotalSeconds("生成两分半的视频"))
                .isEqualTo(150);
    }

    @Test
    void allocate_weightsLongerScenesWithinModelLimits() {
        SceneDurationPlanner.Allocation allocation =
                SceneDurationPlanner.allocate(30, List.of("短", "中等长度的分镜内容", "这是一段明显更长的分镜内容"));

        assertThat(allocation.feasible()).isTrue();
        assertThat(allocation.durations().stream().mapToInt(Integer::intValue).sum()).isEqualTo(30);
        assertThat(allocation.durations()).allMatch(value ->
                value >= SceneDurationPlanner.MIN_SCENE_DURATION
                        && value <= SceneDurationPlanner.MAX_SCENE_DURATION);
        assertThat(allocation.durations().get(2)).isGreaterThan(allocation.durations().get(0));
    }
}
