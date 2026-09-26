package com.moon.moonagent.ai.agent.agentscope.nacos;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.prompt.Prompt;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlobalPromptRepositoryTest {

    @Test
    void usesClasspathPromptWhenNacosIsDisabled() {
        NacosAssetProperties properties = new NacosAssetProperties();
        GlobalPromptRepository repository = new GlobalPromptRepository(
                properties,
                () -> {
                    throw new AssertionError("Nacos should not be contacted while disabled");
                },
                resource("Moon {{name}}"));

        assertThat(repository.getPrompt(Map.of("name", "Agent"))).isEqualTo("Moon Agent");
        assertThat(repository.selectedSource()).isEqualTo("classpath");
    }

    @Test
    void fallsBackWhenNacosPromptLookupFails() {
        NacosAssetProperties properties = new NacosAssetProperties();
        properties.setEnabled(true);
        GlobalPromptRepository repository = new GlobalPromptRepository(
                properties,
                () -> {
                    throw new IllegalStateException("Nacos unavailable");
                },
                resource("fallback"));

        assertThat(repository.getPrompt(Map.of())).isEqualTo("fallback");
        assertThat(repository.selectedSource()).isEqualTo("classpath");
    }

    @Test
    void configuredNacosPromptVersionWinsOverLabel() throws Exception {
        NacosAssetProperties properties = new NacosAssetProperties();
        properties.setEnabled(true);
        properties.setPromptKey("global");
        properties.setPromptVersion("2.0.0");
        properties.setPromptLabel("stable");
        AiService aiService = mock(AiService.class);
        when(aiService.subscribePrompt(eq("global"), eq("2.0.0"), isNull(), any()))
                .thenReturn(new Prompt("global", "2.0.0", "nacos prompt"));
        GlobalPromptRepository repository = new GlobalPromptRepository(
                properties, () -> aiService, resource("fallback"));

        assertThat(repository.getPrompt(Map.of())).isEqualTo("nacos prompt");
        verify(aiService).subscribePrompt(eq("global"), eq("2.0.0"), isNull(), any());
        assertThat(repository.selectedSource()).isEqualTo("nacos");
    }

    private static ByteArrayResource resource(String value) {
        return new ByteArrayResource(value.getBytes(StandardCharsets.UTF_8));
    }
}
