package com.moon.moonagent.ai.agent.agentscope.nacos;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import io.agentscope.core.nacos.skill.NacosSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.util.Properties;

@Configuration(proxyBeanMethods = false)
public class NacosAssetConfiguration {

    @Bean
    @ConditionalOnMissingBean(NacosAiServiceFactory.class)
    NacosAiServiceFactory nacosAiServiceFactory() {
        return new DefaultNacosAiServiceFactory();
    }

    /**
     * Kept lazy so an unavailable Nacos server cannot fail application startup.
     */
    @Bean(destroyMethod = "shutdown")
    @Lazy
    @ConditionalOnProperty(
            prefix = "ai.agent.agentscope.nacos",
            name = "enabled",
            havingValue = "true")
    AiService agentScopeNacosAiService(
            NacosAssetProperties properties,
            NacosAiServiceFactory factory) throws NacosException {
        return factory.create(properties);
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(AgentSkillRepository.class)
    AgentSkillRepository agentSkillRepository(
            NacosAssetProperties properties,
            ObjectProvider<AiService> aiServiceProvider) throws IOException {
        AgentSkillRepository fallback =
                new ClasspathSkillRepository(properties.getSkillFallbackLocation(), "classpath");
        return new ResilientAgentSkillRepository(
                properties.getSkillNames(),
                properties.isAllowClasspathFallback(),
                () -> {
                    if (!properties.isEnabled()) {
                        return null;
                    }
                    AiService aiService = aiServiceProvider.getIfAvailable();
                    if (aiService == null) {
                        return null;
                    }
                    Properties skillProperties = new Properties();
                    skillProperties.setProperty(
                            NacosSkillRepository.SKILL_VERSION_PATH,
                            properties.getSkillVersion());
                    skillProperties.setProperty(
                            NacosSkillRepository.SKILL_LABEL_PATH,
                            properties.getSkillLabel());
                    return new NacosSkillRepository(
                            aiService,
                            properties.getNamespace(),
                            skillProperties,
                            properties.getSkillNames());
                },
                fallback);
    }

    @Bean
    @ConditionalOnMissingBean(GlobalPromptRepository.class)
    GlobalPromptRepository globalPromptRepository(
            NacosAssetProperties properties,
            ObjectProvider<AiService> aiServiceProvider,
            ResourceLoader resourceLoader) {
        Resource fallback = resourceLoader.getResource(
                "classpath:" + properties.getGlobalPromptFallbackLocation());
        return new GlobalPromptRepository(
                properties,
                () -> properties.isEnabled() ? aiServiceProvider.getIfAvailable() : null,
                fallback);
    }
}
