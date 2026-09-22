package com.moon.moonagent.ai.agent.capability;

import com.moon.moonagent.ai.agent.skill.AgentSkill;
import com.moon.moonagent.ai.agent.skill.SkillManager;
import com.moon.moonagent.ai.agent.specialist.SpecialistAgent;
import com.moon.moonagent.ai.agent.specialist.SpecialistAgentRegistry;
import com.moon.moonagent.orchestration.CapabilityDescriptor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class AgentCapabilityRegistry {

    private final SkillManager skillManager;
    private final SpecialistAgentRegistry specialistRegistry;
    private Map<String, AgentCapability> registry = Map.of();

    @jakarta.annotation.PostConstruct
    void init() {
        Map<String, AgentCapability> capabilities = new LinkedHashMap<>();
        for (AgentSkill skill : skillManager.all()) {
            Optional<SpecialistAgent> specialist = specialistRegistry.get(skill.intentType());
            if (specialist.isEmpty()) continue;
            CapabilityDescriptor descriptor = descriptor(skill);
            capabilities.put(skill.intentType(), new SpecialistCapabilityAdapter(skill, specialist.get(), descriptor));
        }
        registry = Map.copyOf(capabilities);
    }

    public Optional<AgentCapability> find(String key) {
        return Optional.ofNullable(registry.get(key));
    }

    public Map<String, CapabilityDescriptor> descriptors() {
        Map<String, CapabilityDescriptor> descriptors = new LinkedHashMap<>();
        registry.forEach((key, capability) -> descriptors.put(key, capability.descriptor()));
        return Map.copyOf(descriptors);
    }

    private static CapabilityDescriptor descriptor(AgentSkill skill) {
        int maxAttempts = skill.limits() == null
                ? 3
                : skill.limits().getOrDefault("max_attempts", 3);
        return new CapabilityDescriptor(
                skill.intentType(),
                skill.label() == null || skill.label().isBlank() ? skill.name() : skill.label(),
                skill.description(),
                skill.tools(),
                skill.async(),
                skill.defaultHitlPolicy(),
                skill.evaluationMode(),
                maxAttempts);
    }
}
