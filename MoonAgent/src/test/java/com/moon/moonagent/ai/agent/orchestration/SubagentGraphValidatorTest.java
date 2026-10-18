package com.moon.moonagent.ai.agent.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubagentGraphValidatorTest {

    @Test
    void acceptsDiamondAndSplitsIntoLevels() {
        SubagentTaskGraph graph = new SubagentTaskGraph("diamond", List.of(
                task("a"),
                task("b", "a"),
                task("c", "a"),
                task("d", "b", "c")), 2, false);

        List<List<SubagentTask>> levels = SubagentGraphValidator.validateAndLevels(graph);

        assertThat(levels).hasSize(3);
        assertThat(levels.get(0)).extracting(SubagentTask::id).containsExactly("a");
        assertThat(levels.get(1)).extracting(SubagentTask::id).containsExactlyInAnyOrder("b", "c");
        assertThat(levels.get(2)).extracting(SubagentTask::id).containsExactly("d");
    }

    @Test
    void rejectsCycles() {
        SubagentTaskGraph graph = new SubagentTaskGraph("cycle", List.of(
                task("a", "b"), task("b", "a")), 2, false);

        assertThatThrownBy(() -> SubagentGraphValidator.validateAndLevels(graph))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("环");
    }

    @Test
    void rejectsUnknownDependencyAndDuplicateIds() {
        assertThatThrownBy(() -> SubagentGraphValidator.validateAndLevels(
                new SubagentTaskGraph("unknown", List.of(task("a", "missing")), 2, false)))
                .hasMessageContaining("不存在");

        assertThatThrownBy(() -> SubagentGraphValidator.validateAndLevels(
                new SubagentTaskGraph("dup", List.of(task("a"), task("a")), 2, false)))
                .hasMessageContaining("重复");
    }

    @Test
    void rejectsTooMuchFanoutAndTooDeepGraphs() {
        SubagentTaskGraph fanout = new SubagentTaskGraph("fanout", List.of(
                task("root"),
                task("c1", "root"), task("c2", "root"), task("c3", "root"),
                task("c4", "root"), task("c5", "root"), task("c6", "root"),
                task("c7", "root")), 4, false);
        assertThatThrownBy(() -> SubagentGraphValidator.validateAndLevels(fanout))
                .hasMessageContaining("下游超过");

        SubagentTaskGraph deep = new SubagentTaskGraph("deep", List.of(
                task("a"), task("b", "a"), task("c", "b"), task("d", "c")), 2, false);
        assertThatThrownBy(() -> SubagentGraphValidator.validateAndLevels(deep))
                .hasMessageContaining("深度超过");
    }

    @Test
    void rejectsEmptyGraph() {
        assertThatThrownBy(() -> SubagentGraphValidator.validateAndLevels(
                new SubagentTaskGraph("empty", List.of(), 2, false)))
                .hasMessageContaining("不能为空");
    }

    private static SubagentTask task(String id, String... dependsOn) {
        return new SubagentTask(id, "worker", "do " + id,
                List.of(dependsOn), List.of(), List.of(), 2);
    }
}
