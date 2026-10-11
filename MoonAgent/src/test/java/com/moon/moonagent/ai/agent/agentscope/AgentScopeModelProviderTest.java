package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.AgentContextWindowResolver;
import com.moon.moonagent.ai.agent.creative.CreativeModelRouter;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentScopeModelProviderTest {

    @Test
    void buildsPrimaryAndFallbackModelsWithResolvedContextWindows() {
        AgentScopeProperties properties = new AgentScopeProperties();
        properties.setModel("primary-model");
        properties.setFallbackModel("fallback-model");
        CreativeModelRouter router = mock(CreativeModelRouter.class);
        AgentContextWindowResolver windows = mock(AgentContextWindowResolver.class);
        when(windows.resolve("primary-model")).thenReturn(321_000);
        when(windows.resolve("fallback-model")).thenReturn(654_000);
        AgentScopeModelProvider provider = new AgentScopeModelProvider(properties, router, windows);
        ReflectionTestUtils.setField(provider, "gatewayBaseUrl", "http://gateway.local");
        ReflectionTestUtils.setField(provider, "gatewayApiKey", "test-key");

        assertThat(provider.primary().getModelName()).isEqualTo("primary-model");
        assertThat(provider.primary().getContextWindowSize()).isEqualTo(321_000);
        assertThat(provider.fallback()).isNotNull();
        assertThat(provider.fallback().getModelName()).isEqualTo("fallback-model");
        assertThat(provider.fallback().getContextWindowSize()).isEqualTo(654_000);
    }

    @Test
    void omitsFallbackWhenConfiguredNameMatchesPrimary() {
        AgentScopeProperties properties = new AgentScopeProperties();
        properties.setModel("same-model");
        properties.setFallbackModel("same-model");
        CreativeModelRouter router = mock(CreativeModelRouter.class);
        AgentContextWindowResolver windows = mock(AgentContextWindowResolver.class);
        when(windows.resolve("same-model")).thenReturn(128_000);
        AgentScopeModelProvider provider = new AgentScopeModelProvider(properties, router, windows);
        ReflectionTestUtils.setField(provider, "gatewayBaseUrl", "http://gateway.local");
        ReflectionTestUtils.setField(provider, "gatewayApiKey", "test-key");

        assertThat(provider.fallback()).isNull();
    }
}
