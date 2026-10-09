package com.storyboard.ai;

import com.moon.moonagent.ai.AiConfigProperties;
import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.SceneGenerationBridge;
import com.moon.moonagent.ai.VideoGenerationService;
import com.moon.moonagent.ai.impl.VideoPollExecutor;
import com.moon.moonagent.service.FileStorageService;
import com.storyboard.common.generation.GenerationRecordWriter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.image.ImageModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentCoreAiConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(AiConfigProperties.class,
                    () -> new AiConfigProperties(new AiConfigProperties.Gateway()))
            .withBean(FileStorageService.class, () -> mock(FileStorageService.class))
            .withBean(SceneGenerationBridge.class, () -> mock(SceneGenerationBridge.class))
            .withBean(GenerationRecordWriter.class, () -> mock(GenerationRecordWriter.class))
            .withBean(ImageModel.class, () -> mock(ImageModel.class))
            .withUserConfiguration(AgentCoreAiConfiguration.class);

    @Test
    void registersOnlySharedAgentCoreMediaBeans() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(GatewayModelService.class);
            assertThat(context).hasSingleBean(com.moon.moonagent.ai.ImageGenerationService.class);
            assertThat(context).hasSingleBean(VideoGenerationService.class);
            assertThat(context).hasSingleBean(VideoPollExecutor.class);
            assertThat(context).doesNotHaveBean("springAiStoryboardPlanningService");
        });
    }
}
