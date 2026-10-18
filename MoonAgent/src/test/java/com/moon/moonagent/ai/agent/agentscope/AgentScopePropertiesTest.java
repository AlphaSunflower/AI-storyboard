package com.moon.moonagent.ai.agent.agentscope;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopePropertiesTest {

    @Test
    void toolExecutionTimeoutMustCoverTwoTaskGraphPasses() {
        AgentScopeProperties properties = new AgentScopeProperties();

        assertThat(properties.getToolExecutionTimeout().toMillis())
                .as("ToolExecutor 的 5 分钟默认值不足以覆盖初跑+修复")
                .isGreaterThan(properties.getSubagentMaxDurationMs() * 2L);
        assertThat(properties.getAsyncToolTimeout())
                .isGreaterThan(properties.getToolExecutionTimeout());
    }

    @Test
    void asyncToolTimeoutMustCoverTheWholeSubagentGraphBudget() {
        AgentScopeProperties properties = new AgentScopeProperties();

        assertThat(properties.getAsyncToolTimeout().toMillis())
                .as("run_task_graph 不能在任务图预算内被 AsyncToolMiddleware 后台化")
                .isGreaterThan(properties.getSubagentMaxDurationMs());
    }

    @Test
    void imageBatchConcurrencyDefaultsToThreeAndIsClamped() {
        AgentScopeProperties properties = new AgentScopeProperties();

        assertThat(properties.getImageBatchConcurrency()).isEqualTo(3);

        properties.setImageBatchConcurrency(0);
        assertThat(properties.getImageBatchConcurrency()).isEqualTo(1);

        properties.setImageBatchConcurrency(99);
        assertThat(properties.getImageBatchConcurrency()).isEqualTo(8);
    }
}
