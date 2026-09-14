package com.moon.moonagent.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAiConfigPropertiesTest {

    @Test
    void defaultPlanTimeoutIsTenMinutes() {
        assertThat(new AgentAiConfigProperties().getPlanTimeoutSeconds()).isEqualTo(600);
    }

    @Test
    void defaultSseTimeoutCoversLongBatchGeneration() {
        assertThat(new AgentAiConfigProperties().getSseTimeoutSeconds()).isEqualTo(1800);
    }

    @Test
    void defaultSkeletonModelIsExplicitFastModel() {
        assertThat(new AgentAiConfigProperties().getSkeletonModel()).isEqualTo("mimo-v2.5-pro");
    }

    @Test
    void defaultMaxParallelAgentsIsFour() {
        assertThat(new AgentAiConfigProperties().getMaxParallelAgents()).isEqualTo(4);
    }
}
