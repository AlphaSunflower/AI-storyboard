package com.moon.moonagent.ai.agent.agentscope.nacos;

import com.alibaba.nacos.api.ai.AiService;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class NacosAssetConfigurationTest {

    @Test
    void startsWithClasspathFallbackWithoutCreatingNacosClient() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(NacosAssetProperties.class);
            context.register(NacosAssetConfiguration.class);
            context.refresh();

            assertThat(context.getBean(AgentSkillRepository.class)).isNotNull();
            assertThat(context.getBean(GlobalPromptRepository.class)).isNotNull();
            assertThat(context.getBeansOfType(AiService.class)).isEmpty();
        }
    }
}
