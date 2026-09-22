package com.moon.moonagent.ai.agent.capability;

import com.moon.moonagent.ai.agent.skill.AgentSkill;
import com.moon.moonagent.ai.agent.skill.SkillManager;
import com.moon.moonagent.ai.agent.specialist.SpecialistAgent;
import com.moon.moonagent.ai.agent.specialist.SpecialistAgentRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentCapabilityRegistryTest {

    @Test
    void exposesRegisteredSkillBackedSpecialistsAsCapabilities() {
        SkillManager skillManager = mock(SkillManager.class);
        SpecialistAgentRegistry specialists = mock(SpecialistAgentRegistry.class);
        SpecialistAgent specialist = mock(SpecialistAgent.class);
        AgentSkill skill = new AgentSkill(
                "intent-pic", "pic", "image generation", "图片设计",
                "image", List.of("generate_image"), Map.of(), List.of(), 50, 20,
                List.of(Map.of("id", "generate_image", "title", "生成图片")), Map.of(), List.of());

        when(skillManager.all()).thenReturn(List.of(skill));
        when(specialists.get("intent-pic")).thenReturn(Optional.of(specialist));

        AgentCapabilityRegistry registry = new AgentCapabilityRegistry(skillManager, specialists);
        registry.init();

        assertThat(registry.descriptors()).containsKey("intent-pic");
        assertThat(registry.descriptors().get("intent-pic").title()).isEqualTo("图片设计");
        assertThat(registry.find("intent-pic")).isPresent();
        assertThat(registry.find("intent-missing")).isEmpty();
    }
}
