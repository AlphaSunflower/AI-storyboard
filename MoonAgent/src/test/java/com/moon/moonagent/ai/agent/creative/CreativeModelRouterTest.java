package com.moon.moonagent.ai.agent.creative;

import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.agent.agentscope.AgentScopeProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CreativeModelRouterTest {

    @Test
    void roleOverridesBeatGatewayDefaults() {
        AgentScopeProperties properties = new AgentScopeProperties();
        properties.getModels().setPlan("plan-model");
        properties.getModels().setCritic("critic-model");
        GatewayModelService gateway = mock(GatewayModelService.class);
        when(gateway.getDefaultTextModel()).thenReturn("default-text");
        when(gateway.getDefaultVisionModel()).thenReturn("default-vision");
        CreativeModelRouter router = new CreativeModelRouter(properties, gateway);

        assertThat(router.resolve("creative.plan")).isEqualTo("plan-model");
        assertThat(router.resolve("creative.critic")).isEqualTo("critic-model");
        assertThat(router.resolve("creative.prompt")).isEqualTo("default-text");
        assertThat(router.resolve("vision.qa")).isEqualTo("critic-model");
    }
}
