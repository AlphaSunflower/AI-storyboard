package com.moon.moonagent.ai.impl;

import com.moon.moonagent.ai.AiConfigProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptGenerationServiceImplTest {

    @Test
    void buildRequestOptions_disablesSdkRetriesAndAddsCallId() {
        AiConfigProperties config = new AiConfigProperties(new AiConfigProperties.Gateway());
        config.setScriptTimeout(300);
        config.setScriptMaxTokens(8192);
        ScriptGenerationServiceImpl service = new ScriptGenerationServiceImpl(
                null, config, null, null, null, null);

        OpenAiChatOptions.Builder builder = ReflectionTestUtils.invokeMethod(
                service, "buildRequestOptions", "test-model", "call-123");
        OpenAiChatOptions options = builder.build();

        assertThat(options.getMaxRetries()).isZero();
        assertThat(options.getTimeout()).isEqualTo(Duration.ofSeconds(300));
        assertThat(options.getMaxTokens()).isEqualTo(8192);
        assertThat(options.getCustomHeaders()).containsEntry("X-Call-Id", "call-123");
    }
}
