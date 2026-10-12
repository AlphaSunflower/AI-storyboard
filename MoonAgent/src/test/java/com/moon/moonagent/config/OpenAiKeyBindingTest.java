package com.moon.moonagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiKeyBindingTest {

    /**
     * 断言自带网关 key。`spring.ai.openai.api-key` 会回退到 `ai.gateway.api-key`，
     * 而后者过去只由被 gitignore 的 application-local.yml 提供，CI 上没有该文件时
     * 两个属性都解析成空串，断言就永远失败。
     */
    private static final String GATEWAY_KEY = "test-gateway-key";

    @Test
    void springAiOpenAiKeyFallsBackToGatewayKey() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "spring.profiles.active=local",
                        "ai.gateway.api-key=" + GATEWAY_KEY)
                .run(context -> {
                    assertThat(context.getEnvironment().getProperty("ai.gateway.api-key"))
                            .isEqualTo(GATEWAY_KEY);
                    assertThat(context.getEnvironment().getProperty("spring.ai.openai.api-key"))
                            .isEqualTo(GATEWAY_KEY);
                });
    }
}
