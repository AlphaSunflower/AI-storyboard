package com.moon.moonagent.ai.agent.capability;

import com.moon.moonagent.ai.agent.skill.AgentSkill;
import com.moon.moonagent.ai.agent.skill.SkillManager;
import com.moon.moonagent.ai.agent.specialist.SpecialistAgent;
import com.moon.moonagent.ai.agent.specialist.SpecialistAgentRegistry;
import com.moon.moonagent.ai.agent.specialist.SpecialistResult;
import com.moon.moonagent.orchestration.EvaluationMode;
import com.moon.moonagent.orchestration.HitlPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCapabilityRegistryTest {

    @Test
    void exposesRegisteredSkillBackedSpecialistsAsCapabilities() {
        SkillManager skillManager = mock(SkillManager.class);
        SpecialistAgentRegistry specialists = mock(SpecialistAgentRegistry.class);
        SpecialistAgent specialist = mock(SpecialistAgent.class);
        AgentSkill skill = AgentSkill.of("pic", Map.of(
                "description", "image generation",
                "label", "图片设计",
                "model_role", "image",
                "tools", List.of("generate_image"),
                "limits", Map.of("max_attempts", 5),
                "evaluation_mode", "deterministic",
                "default_hitl_policy", "after",
                "async", false,
                "hitl", List.of(Map.of("id", "generate_image", "title", "生成图片"))
        ), Map.of());

        when(skillManager.all()).thenReturn(List.of(skill));
        when(specialists.get("intent-pic")).thenReturn(Optional.of(specialist));
        when(specialist.execute(org.mockito.ArgumentMatchers.any())).thenReturn(SpecialistResult.done("ok"));

        AgentCapabilityRegistry registry = new AgentCapabilityRegistry(skillManager, specialists);
        registry.init();

        assertThat(registry.descriptors()).containsKey("intent-pic");
        assertThat(registry.descriptors().get("intent-pic").title()).isEqualTo("图片设计");
        assertThat(registry.descriptors().get("intent-pic").evaluationMode()).isEqualTo(EvaluationMode.DETERMINISTIC);
        assertThat(registry.descriptors().get("intent-pic").defaultHitlPolicy()).isEqualTo(HitlPolicy.AFTER);
        assertThat(registry.descriptors().get("intent-pic").async()).isFalse();
        assertThat(registry.descriptors().get("intent-pic").maxAttempts()).isEqualTo(5);
        assertThat(registry.find("intent-pic")).isPresent();
        assertThat(registry.find("intent-missing")).isEmpty();
        registry.find("intent-pic").orElseThrow().execute(null);
        verify(specialist).execute(null);
    }
}
