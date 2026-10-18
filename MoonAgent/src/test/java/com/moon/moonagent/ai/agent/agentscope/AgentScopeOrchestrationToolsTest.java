package com.moon.moonagent.ai.agent.agentscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.orchestration.SubagentRoleCatalog;
import com.moon.moonagent.ai.agent.orchestration.SubagentTask;
import com.moon.moonagent.ai.agent.orchestration.SubagentTaskGraph;
import com.moon.moonagent.ai.agent.orchestration.SubagentTaskResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentScopeOrchestrationToolsTest {

    private final AgentScopeOrchestrationTools tools = new AgentScopeOrchestrationTools(
            new SubagentRoleCatalog(),
            (task, dependencies, cancellation) ->
                    SubagentTaskResult.completed(task.id(), "out-" + task.id(), 1, 1));

    @Test
    void keepsSharedContextWhenRunningUnderAParentTurnScope() {
        java.util.concurrent.atomic.AtomicReference<String> seen =
                new java.util.concurrent.atomic.AtomicReference<>("");
        AgentScopeOrchestrationTools withContext = new AgentScopeOrchestrationTools(
                new SubagentRoleCatalog(),
                new com.moon.moonagent.ai.agent.orchestration.SubagentNodeExecutor() {
                    @Override
                    public com.moon.moonagent.ai.agent.orchestration.SubagentTaskResult execute(
                            com.moon.moonagent.ai.agent.orchestration.SubagentTask task,
                            java.util.Map<String,
                                    com.moon.moonagent.ai.agent.orchestration.SubagentTaskResult> deps,
                            com.moon.moonagent.ai.agent.orchestration.SubagentCancellation cancellation) {
                        return execute(task, deps, cancellation, "");
                    }

                    @Override
                    public com.moon.moonagent.ai.agent.orchestration.SubagentTaskResult execute(
                            com.moon.moonagent.ai.agent.orchestration.SubagentTask task,
                            java.util.Map<String,
                                    com.moon.moonagent.ai.agent.orchestration.SubagentTaskResult> deps,
                            com.moon.moonagent.ai.agent.orchestration.SubagentCancellation cancellation,
                            String context) {
                        seen.set(context);
                        return com.moon.moonagent.ai.agent.orchestration.SubagentTaskResult
                                .completed(task.id(), "ok", 1, 1);
                    }
                },
                null, new AgentScopeProperties());

        Map<String, Object> input = Map.of(
                "task", "上下文",
                "repair", false,
                "context", "CANONICAL-CONTEXT-123",
                "nodes", List.of(node("research", "researcher")));

        withContext.runGraph(input, new AgentScopeRunContext(null, null, null, null));

        assertThat(seen.get()).isEqualTo("CANONICAL-CONTEXT-123");
    }

    @Test
    void passesSharedContextToEveryNode() {
        java.util.concurrent.atomic.AtomicReference<String> seen =
                new java.util.concurrent.atomic.AtomicReference<>("");
        AgentScopeOrchestrationTools withContext = new AgentScopeOrchestrationTools(
                new SubagentRoleCatalog(),
                new com.moon.moonagent.ai.agent.orchestration.SubagentNodeExecutor() {
                    @Override
                    public com.moon.moonagent.ai.agent.orchestration.SubagentTaskResult execute(
                            com.moon.moonagent.ai.agent.orchestration.SubagentTask task,
                            java.util.Map<String,
                                    com.moon.moonagent.ai.agent.orchestration.SubagentTaskResult> deps,
                            com.moon.moonagent.ai.agent.orchestration.SubagentCancellation cancellation) {
                        return execute(task, deps, cancellation, "");
                    }

                    @Override
                    public com.moon.moonagent.ai.agent.orchestration.SubagentTaskResult execute(
                            com.moon.moonagent.ai.agent.orchestration.SubagentTask task,
                            java.util.Map<String,
                                    com.moon.moonagent.ai.agent.orchestration.SubagentTaskResult> deps,
                            com.moon.moonagent.ai.agent.orchestration.SubagentCancellation cancellation,
                            String context) {
                        seen.set(context);
                        return com.moon.moonagent.ai.agent.orchestration.SubagentTaskResult
                                .completed(task.id(), "ok", 1, 1);
                    }
                });

        Map<String, Object> input = Map.of(
                "task", "上下文",
                "repair", false,
                "context", "CANONICAL-CONTEXT-123",
                "nodes", List.of(node("research", "researcher")));

        withContext.runGraph(input);

        assertThat(seen.get()).isEqualTo("CANONICAL-CONTEXT-123");
    }

    @Test
    void filtersWriteToolsByUserApproval() {
        AgentScopeOrchestrationTools tools = new AgentScopeOrchestrationTools(
                new SubagentRoleCatalog(),
                (task, deps, cancellation) -> com.moon.moonagent.ai.agent.orchestration
                        .SubagentTaskResult.completed(task.id(), "ok", 1, 1));
        SubagentTask writer = new SubagentTask("writer", "writer", "写文件",
                List.of(), List.of(), List.of("read_file", "write_file", "run_python_script"),
                4, null, true);
        SubagentTaskGraph graph = new SubagentTaskGraph("write", List.of(writer), 1, false);

        SubagentTaskGraph filtered = tools.filterWriteTools(graph, "write_file");

        assertThat(filtered.nodes().getFirst().tools())
                .contains("read_file", "write_file")
                .doesNotContain("run_python_script");
        assertThatThrownBy(() -> tools.filterWriteTools(graph, "read_file"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有获批的写工具");
    }

    @Test
    void writeNodesRequireTheWriteTaskGraphTool() {
        AgentScopeOrchestrationTools writeTools = new AgentScopeOrchestrationTools(
                new SubagentRoleCatalog(),
                (task, deps, cancellation) -> com.moon.moonagent.ai.agent.orchestration
                        .SubagentTaskResult.completed(task.id(), "ok", 1, 1));
        Map<String, Object> input = Map.of(
                "task", "写文件",
                "repair", false,
                "nodes", List.of(Map.of(
                        "id", "writer", "role", "writer",
                        "instruction", "写一个文件", "allowWrite", true)));

        assertThatThrownBy(() -> writeTools.runGraph(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("run_write_task_graph");
        assertThat(writeTools.runGraph(input, null, null, true).get("results"))
                .asList().isNotEmpty();
    }

    @Test
    void runsTheStoryboardPilotGraph() {
        Map<String, Object> input = Map.of(
                "task", "分镜试点",
                "repair", false,
                "nodes", List.of(
                        node("research", "researcher"),
                        node("script", "screenwriter", "research"),
                        node("visual", "visual-director", "research"),
                        node("review", "reviewer", "script", "visual")));

        Map<String, Object> result = tools.runGraph(input);

        assertThat(result.get("success")).isEqualTo(true);
        assertThat((List<?>) result.get("results")).hasSize(4);
        assertThat((List<?>) result.get("results"))
                .allMatch(row -> ((Map<?, ?>) row).get("status").equals("COMPLETED"));
    }

    @Test
    void runsOneBoundedRepairRoundWhenReviewRejects() {
        AgentScopeOrchestrationTools repairing = new AgentScopeOrchestrationTools(
                new SubagentRoleCatalog(),
                (task, dependencies, cancellation) -> {
                    if ("review".equals(task.id())) {
                        return SubagentTaskResult.completed(task.id(), "不通过：fps 不一致", 1, 1);
                    }
                    if ("review-2".equals(task.id())) {
                        return SubagentTaskResult.completed(task.id(), "通过", 1, 1);
                    }
                    return SubagentTaskResult.completed(task.id(), "ok", 1, 1);
                });

        Map<String, Object> input = Map.of(
                "task", "修复轮",
                "nodes", List.of(
                        node("script", "screenwriter"),
                        node("visual", "visual-director", "script"),
                        node("review", "reviewer", "script", "visual")));

        Map<String, Object> result = repairing.runGraph(input);

        assertThat(result.get("repairRounds")).isEqualTo(1);
        assertThat((List<?>) result.get("results"))
                .anyMatch(row -> ((Map<?, ?>) row).get("id").equals("review-2")
                        && ((Map<?, ?>) row).get("output").equals("通过"));
    }

    @Test
    void rejectsUnknownRolesBeforeScheduling() {
        Map<String, Object> input = Map.of(
                "task", "bad",
                "nodes", List.of(node("a", "ghost")));

        assertThatThrownBy(() -> tools.runGraph(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未登记");
    }

    private static Map<String, Object> node(String id, String role, String... dependsOn) {
        return Map.of(
                "id", id,
                "role", role,
                "instruction", "do " + id,
                "dependsOn", List.of(dependsOn));
    }
}
