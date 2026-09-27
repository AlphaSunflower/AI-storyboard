package com.moon.moonagent.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAiConfigPropertiesTest {

    @Test
    void defaultPlanTimeoutSupportsLongCanvasTasks() {
        assertThat(new AgentAiConfigProperties().getPlanTimeoutSeconds()).isEqualTo(1800);
    }

    @Test
    void defaultSseTimeoutCoversLongCanvasSessions() {
        assertThat(new AgentAiConfigProperties().getSseTimeoutSeconds()).isEqualTo(14400);
    }

    @Test
    void defaultSkeletonModelIsExplicitFastModel() {
        assertThat(new AgentAiConfigProperties().getSkeletonModel()).isEqualTo("mimo-v2.5-pro");
    }

    @Test
    void defaultMaxParallelAgentsIsFour() {
        assertThat(new AgentAiConfigProperties().getMaxParallelAgents()).isEqualTo(4);
    }

    @Test
    void humanInputIsDisabledByDefault() {
    }
}
