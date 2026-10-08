package com.moon.moonagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiKeyBindingTest {

    @Test
    void springAiOpenAiKeyFallsBackToGatewayKey() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=local")
                .run(context -> assertThat(context.getEnvironment()
                        .getProperty("spring.ai.openai.api-key"))
                        .isNotBlank()
                        .isEqualTo(context.getEnvironment()
                                .getProperty("ai.gateway.api-key")));
    }
}
