package com.moon.moonagent.ai.impl;

import com.moon.moonagent.ai.AiConfigProperties;
import com.moon.moonagent.ai.AgentAiConfigProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScriptGenerationServiceImplTest {

    @Test
    void buildRequestOptions_disablesSdkRetriesAndAddsCallId() {
        AiConfigProperties config = new AiConfigProperties(new AiConfigProperties.Gateway());
        config.setScriptTimeout(300);
        config.setScriptMaxTokens(8192);
        ScriptGenerationServiceImpl service = new ScriptGenerationServiceImpl(
                null, config, new AgentAiConfigProperties(), null, null, null, null);

        OpenAiChatOptions.Builder builder = ReflectionTestUtils.invokeMethod(
                service, "buildRequestOptions", "test-model", "call-123");
        OpenAiChatOptions options = builder.build();

        assertThat(options.getMaxRetries()).isZero();
        assertThat(options.getTimeout()).isEqualTo(Duration.ofSeconds(300));
        assertThat(options.getMaxTokens()).isEqualTo(8192);
        assertThat(options.getCustomHeaders()).containsEntry("X-Call-Id", "call-123");
    }

    @Test
    void resolveSkeletonModel_usesExplicitFastModel() {
        AgentAiConfigProperties agentConfig = new AgentAiConfigProperties();
        agentConfig.setSkeletonModel("fast-skeleton");
        ScriptGenerationServiceImpl service = new ScriptGenerationServiceImpl(
                null, new AiConfigProperties(new AiConfigProperties.Gateway()),
                agentConfig, null, null, null, null);

        assertThat(service.resolveSkeletonModel()).isEqualTo("fast-skeleton");
    }

    @Test
    void resolveSkeletonModel_rejectsBlankInsteadOfGatewayDefault() {
        AgentAiConfigProperties agentConfig = new AgentAiConfigProperties();
        agentConfig.setSkeletonModel(" ");
        ScriptGenerationServiceImpl service = new ScriptGenerationServiceImpl(
                null, new AiConfigProperties(new AiConfigProperties.Gateway()),
                agentConfig, null, null, null, null);

        assertThatThrownBy(service::resolveSkeletonModel)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skeleton-model");
    }
}
