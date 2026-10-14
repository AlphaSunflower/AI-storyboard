package com.moon.moonagent.ai.agent.agentscope;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopeWebSearchPropertiesTest {

    @Test
    void selfHostedDefaultNeedsNoApiKey() {
        AgentScopeProperties properties = new AgentScopeProperties();

        // 默认后端是自托管 SearXNG：只要实例地址在就算配置齐全，不需要任何第三方 key。
        assertThat(properties.getWebSearch().getProvider())
                .isEqualTo(AgentScopeProperties.WebSearch.Provider.SEARXNG);
        assertThat(properties.getWebSearch().isConfigured()).isTrue();
        assertThat(properties.getWebSearch().getSearxngEndpoint())
                .isEqualTo("http://localhost:8888/search");
    }

    @Test
    void deepSeekProviderNeedsAnApiKey() {
        AgentScopeProperties properties = new AgentScopeProperties();
        properties.getWebSearch().setProvider("deepseek");

        assertThat(properties.getWebSearch().isConfigured()).isFalse();

        properties.getWebSearch().setApiKey("sk-test-key");
        assertThat(properties.getWebSearch().isConfigured()).isTrue();
    }

    @Test
    void configuredOnceApiKeyPresentAndEnabled() {
        AgentScopeProperties properties = new AgentScopeProperties();
        properties.getWebSearch().setProvider("deepseek");
        properties.getWebSearch().setApiKey("sk-test-key");

        assertThat(properties.getWebSearch().isConfigured()).isTrue();
        assertThat(properties.getWebSearch().getBaseUrl())
                .isEqualTo("https://api.deepseek.com/anthropic/v1");
        assertThat(properties.getWebSearch().getModel()).isEqualTo("deepseek-v4-flash");
        assertThat(properties.getWebSearch().getMaxResults()).isEqualTo(8);
    }

    @Test
    void disabledFlagWinsOverApiKey() {
        AgentScopeProperties properties = new AgentScopeProperties();
        properties.getWebSearch().setApiKey("sk-test-key");
        properties.getWebSearch().setEnabled(false);

        assertThat(properties.getWebSearch().isConfigured()).isFalse();
    }

    @Test
    void messagesEndpointAppendsPathOnceAndToleratesTrailingSlash() {
        AgentScopeProperties properties = new AgentScopeProperties();

        assertThat(properties.getWebSearch().getMessagesEndpoint())
                .isEqualTo("https://api.deepseek.com/anthropic/v1/messages");

        properties.getWebSearch().setBaseUrl("https://gateway.internal/anthropic/v1/");
        assertThat(properties.getWebSearch().getMessagesEndpoint())
                .isEqualTo("https://gateway.internal/anthropic/v1/messages");

        properties.getWebSearch().setBaseUrl("  ");
        assertThat(properties.getWebSearch().getBaseUrl())
                .isEqualTo(AgentScopeProperties.WebSearch.DEFAULT_BASE_URL);
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
    void maxUsesAndMaxTokensAreClamped() {
        AgentScopeProperties properties = new AgentScopeProperties();

        assertThat(properties.getWebSearch().getMaxUses()).isEqualTo(5);
        properties.getWebSearch().setMaxUses(0);
        assertThat(properties.getWebSearch().getMaxUses()).isEqualTo(1);
        properties.getWebSearch().setMaxUses(99);
        assertThat(properties.getWebSearch().getMaxUses()).isEqualTo(10);

        assertThat(properties.getWebSearch().getMaxTokens()).isEqualTo(4096);
        properties.getWebSearch().setMaxTokens(1);
        assertThat(properties.getWebSearch().getMaxTokens()).isEqualTo(256);
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

    @Test
    void timeoutDefaultsToFullModelTurnBudget() {
        AgentScopeProperties properties = new AgentScopeProperties();

        assertThat(properties.getWebSearch().getTimeout())
                .isEqualTo(java.time.Duration.ofSeconds(60));

        properties.getWebSearch().setTimeout(null);
        assertThat(properties.getWebSearch().getTimeout())
                .isEqualTo(java.time.Duration.ofSeconds(60));
    }
}
