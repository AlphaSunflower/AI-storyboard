package com.moon.moonagent.ai.agent;

import com.moon.moonagent.ai.GatewayModelService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentContextWindowResolverTest {

    @Test
    void prefersGatewayConfiguredWindow() {
        GatewayModelService gateway = mock(GatewayModelService.class);
        when(gateway.resolveContextWindow("deepseek-v4-flash")).thenReturn(1_000_000);

        AgentContextWindowResolver resolver = new AgentContextWindowResolver(gateway);

        assertThat(resolver.resolve("deepseek-v4-flash")).isEqualTo(1_000_000);
    }

    @Test
    void fallsBackToStaticTableWhenGatewayHasNoValue() {
        GatewayModelService gateway = mock(GatewayModelService.class);
        when(gateway.resolveContextWindow("mimo-v2.6-pro")).thenReturn(null);

        AgentContextWindowResolver resolver = new AgentContextWindowResolver(gateway);

        assertThat(resolver.resolve("mimo-v2.6-pro")).isEqualTo(1_000_000);
        assertThat(resolver.resolve("claude-sonnet-4-5")).isEqualTo(200_000);
        assertThat(resolver.resolve("unknown-model"))
                .isEqualTo(AgentContextWindowResolver.DEFAULT_WINDOW_TOKENS);
    }

    @Test
    void toleratesGatewayFailure() {
        GatewayModelService gateway = mock(GatewayModelService.class);
        when(gateway.resolveContextWindow("gemini-2.5-flash"))
                .thenThrow(new RuntimeException("gateway down"));

        AgentContextWindowResolver resolver = new AgentContextWindowResolver(gateway);

        assertThat(resolver.resolve("gemini-2.5-flash")).isEqualTo(1_000_000);
    }
}
