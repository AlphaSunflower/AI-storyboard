package com.moon.moonagent.ai.agent.agentscope.nacos;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class NacosAssetConfigurationTest {

    @Test
    void startsWithClasspathFallbackWithoutCreatingNacosClient() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton(
                    "nacosAssetProperties", new NacosAssetProperties());
            context.getBeanFactory().registerSingleton("objectMapper", new ObjectMapper());
            context.register(NacosAssetConfiguration.class);
            context.refresh();

            assertThat(context.getBean(AgentSkillRepository.class)).isNotNull();
            assertThat(context.getBean(GlobalPromptRepository.class)).isNotNull();
            assertThat(context.getBeansOfType(AiService.class)).isEmpty();
        }
    }

    @Test
    void staysHealthyWhenEnabledNacosClientCreationFails() {
        NacosAssetProperties properties = new NacosAssetProperties();
        properties.setEnabled(true);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("nacosAssetProperties", properties);
            context.getBeanFactory().registerSingleton("objectMapper", new ObjectMapper());
            context.getBeanFactory().registerSingleton(
                    "failingNacosAiServiceFactory", new FailingAiServiceFactory());
            context.register(NacosAssetConfiguration.class);
            context.refresh();

            AgentSkillRepository repository = context.getBean(AgentSkillRepository.class);
            // classpath 回退里有 6 个常驻技能 + 可选的 ppt-master（默认不挂载，只有引用时才进技能白名单）。
            assertThat(repository.getAllSkills())
                    .extracting(AgentSkill::getName)
                    .containsExactlyInAnyOrder(
                            "image", "video", "storyboard",
                            "canvas-design", "canvas-run", "general", "ppt-master");
            assertThat(context.getBean(GlobalPromptRepository.class).getPrompt(java.util.Map.of()))
                    .contains("你是 Moon 智能体");
            assertThat(context.getBean(NacosMcpMetadataRepository.class).snapshot().source())
                    .isEqualTo("classpath");
        }
    }

    private static final class FailingAiServiceFactory implements NacosAiServiceFactory {

        @Override
        public AiService create(NacosAssetProperties properties) throws NacosException {
            throw new NacosException(NacosException.SERVER_ERROR, "Nacos unavailable");
        }
    }
}
