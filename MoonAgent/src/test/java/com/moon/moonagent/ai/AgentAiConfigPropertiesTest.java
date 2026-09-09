package com.moon.moonagent.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAiConfigPropertiesTest {

    @Test
    void defaultPlanTimeoutIsFiveMinutes() {
        assertThat(new AgentAiConfigProperties().getPlanTimeoutSeconds()).isEqualTo(300);
    }

    @Test
    void defaultSseTimeoutCoversLongBatchGeneration() {
        assertThat(new AgentAiConfigProperties().getSseTimeoutSeconds()).isEqualTo(1200);
    }
}
