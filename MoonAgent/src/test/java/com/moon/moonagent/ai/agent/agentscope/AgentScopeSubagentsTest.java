package com.moon.moonagent.ai.agent.agentscope;

import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentScopeSubagentsTest {

    @Test
    void declaredSubagentsAreReadOnlyAndCannotCreateMedia() {
        var declarations = AgentScopeSubagents.readOnlyExecutors();

        assertThat(declarations).extracting("name")
                .containsExactly(
                        "creative-planner",
                        "prompt-director",
                        "reference-curator",
                        "continuity-editor");
        assertThat(declarations)
                .allSatisfy(declaration ->
                        assertThat(declaration.getTools())
                                .doesNotContain(
                                        AgentScopeImageTools.GENERATE_IMAGE,
                                        AgentScopeImageTools.GENERATE_IMAGE_BATCH,
                                        AgentScopeVideoTools.GENERATE_VIDEO));
        assertThat(declarations).extracting("steps").contains(8, 6, 6, 8);
        assertThat(declarations).extracting("persistSession")
                .containsExactly(true, false, false, true);
    }

    @Test
    void generatedMediaCriticIsNotRegisteredAsSubagent() {
        // 结果诊断子智能体已下线：它会对每次生成结果追加一次付费视觉评审，
        // 并可能替用户决定重生成，首图往往已经是用户想要的结果。
        var declarations = AgentScopeSubagents.readOnlyExecutors();

        assertThat(declarations).extracting("name")
                .doesNotContain("visual-critic");
        assertThat(declarations)
                .allSatisfy(declaration ->
                        assertThat(declaration.getTools())
                                .doesNotContain(AgentScopeCreativeTools.REVIEW_MEDIA));
    }

    @Test
    void registeredAgentSpawnSchemaExposesTaskParameter() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new AgentSpawnTool(
                mock(DefaultAgentManager.class),
                mock(TaskRepository.class),
                0));

        ToolSchema schema = toolkit.getToolSchemas().stream()
                .filter(candidate -> "agent_spawn".equals(candidate.getName()))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) schema.getParameters().get("properties");

        assertThat(properties).containsKey("task");
        assertThat(properties).doesNotContainKey("content");
    }
}
