package com.moon.moonagent.ai.storyboard.production;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 分镜调用的请求选项：必须显式带输出上限（网关不注入 max_tokens），且不得钉死采样参数。
 */
class SpringAiStoryboardChatOptionsTest {

    private static SpringAiStoryboardModelClient client() {
        return new SpringAiStoryboardModelClient(
                mock(org.springframework.ai.chat.client.ChatClient.Builder.class),
                mock(com.moon.moonagent.ai.GatewayModelService.class),
                new com.moon.moonagent.ai.AgentAiConfigProperties(),
                mock(NormalizedChatStreamClient.class));
    }

    @Test
    void alwaysSetsOutputLimitBecauseGatewayDoesNotInjectIt() {
        OpenAiChatOptions options = client().chatOptionsBuilder("deepseek-flash", 60).build();

        assertThat(options.getModel()).isEqualTo("deepseek-flash");
        assertThat(options.getMaxTokens()).isEqualTo(8192);
        assertThat(options.getTimeout()).isNotNull();
    }

    @Test
    void leavesSamplingParametersUntouched() {
        OpenAiChatOptions options = client().chatOptionsBuilder("deepseek-flash", 60).build();

        // temperature / top_p 不传：由上游默认决定，避免替用户钉死创作采样
        assertThat(options.getTemperature()).isNull();
        assertThat(options.getTopP()).isNull();
    }

    @Test
    void clampsTinyTimeoutsToAtLeastOneSecond() {
        assertThat(client().chatOptionsBuilder("m", 0).build().getTimeout())
                .isEqualTo(java.time.Duration.ofSeconds(1));
    }
}
