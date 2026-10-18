package com.moon.moonagent.ai.agent.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SubagentRepairPlannerTest {

    private static final SubagentTaskGraph GRAPH = new SubagentTaskGraph("repair", List.of(
            new SubagentTask("script", "screenwriter", "写脚本", List.of(),
                    List.of(), List.of(), 6),
            new SubagentTask("visual", "visual-director", "画面", List.of("script"),
                    List.of(), List.of(), 6),
            new SubagentTask("review", "reviewer", "评审", List.of("script", "visual"),
                    List.of(), List.of(), 6)), 2, false);

    @Test
    void buildsRepairGraphFromReviewFeedback() {
        SubagentGraphResult result = result("不通过：fps 写成 24，镜1 推进距离不一致");

        assertThat(SubagentRepairPlanner.needsRepair(GRAPH, result)).isTrue();
        SubagentTaskGraph repair = SubagentRepairPlanner.repairGraph(GRAPH, result);

        assertThat(repair).isNotNull();
        assertThat(repair.nodes()).extracting(SubagentTask::id)
                .containsExactlyInAnyOrder("script-fix", "visual-fix", "review-2");
        assertThat(repair.nodes().stream()
                .filter(task -> task.id().equals("visual-fix"))
                .findFirst().orElseThrow().dependsOn()).contains("script-fix");
        assertThat(repair.nodes().stream()
                .filter(task -> task.id().equals("review-2"))
                .findFirst().orElseThrow().dependsOn())
                .containsExactlyInAnyOrder("script-fix", "visual-fix");
        assertThat(repair.nodes().stream()
                .filter(task -> task.id().equals("script-fix"))
                .findFirst().orElseThrow().instruction()).contains("不通过", "fps 写成 24");
    }

    @Test
    void doesNothingWhenReviewPassed() {
        SubagentGraphResult result = result("通过");

        assertThat(SubagentRepairPlanner.needsRepair(GRAPH, result)).isFalse();
    }

    private static SubagentGraphResult result(String reviewOutput) {
        return new SubagentGraphResult("repair", Map.of(
                "script", SubagentTaskResult.completed("script", "ok", 1, 1),
                "visual", SubagentTaskResult.completed("visual", "ok", 1, 1),
                "review", SubagentTaskResult.completed("review", reviewOutput, 1, 1)),
                true, 3, 3, false);
    }
}
