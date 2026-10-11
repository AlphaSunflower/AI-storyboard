package com.moon.moonagent.ai.agent.agentscope;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopeWebSearchPropertiesTest {

    @Test
    void notConfiguredWithoutApiKey() {
        AgentScopeProperties properties = new AgentScopeProperties();

        assertThat(properties.getWebSearch().isConfigured()).isFalse();
    }

    @Test
    void configuredOnceApiKeyPresentAndEnabled() {
        AgentScopeProperties properties = new AgentScopeProperties();
        properties.getWebSearch().setFirecrawlApiKey("fc-test-key");

        assertThat(properties.getWebSearch().isConfigured()).isTrue();
        assertThat(properties.getWebSearch().getFirecrawlUrl())
                .isEqualTo("https://api.firecrawl.dev/v2/search");
        assertThat(properties.getWebSearch().getMaxResults()).isEqualTo(5);
    }

    @Test
    void disabledFlagWinsOverApiKey() {
        AgentScopeProperties properties = new AgentScopeProperties();
        properties.getWebSearch().setFirecrawlApiKey("fc-test-key");
        properties.getWebSearch().setEnabled(false);

        assertThat(properties.getWebSearch().isConfigured()).isFalse();
    }

    @Test
    void maxResultsIsClamped() {
        AgentScopeProperties properties = new AgentScopeProperties();
        properties.getWebSearch().setMaxResults(99);
        assertThat(properties.getWebSearch().getMaxResults()).isEqualTo(10);
        properties.getWebSearch().setMaxResults(0);
        assertThat(properties.getWebSearch().getMaxResults()).isEqualTo(1);
    }

    @Test
    void cacheDefaultsAndClamping() {
        AgentScopeProperties properties = new AgentScopeProperties();

        assertThat(properties.getWebSearch().getCacheTtl())
                .isEqualTo(java.time.Duration.ofMinutes(5));
        assertThat(properties.getWebSearch().getCacheMaxEntries()).isEqualTo(256);

        properties.getWebSearch().setCacheMaxEntries(0);
        assertThat(properties.getWebSearch().getCacheMaxEntries()).isEqualTo(1);
        properties.getWebSearch().setCacheMaxEntries(99_999);
        assertThat(properties.getWebSearch().getCacheMaxEntries()).isEqualTo(4096);
    }
}
