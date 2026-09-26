package com.moon.moonagent.ai.agent.agentscope;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopePropertiesTest {

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
