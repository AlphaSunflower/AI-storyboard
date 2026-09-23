package com.moon.moonagent.ai.agent.capability;

import com.moon.moonagent.ai.agent.specialist.SpecialistAgent;
import com.moon.moonagent.ai.agent.specialist.SpecialistContext;
import com.moon.moonagent.ai.agent.specialist.SpecialistResult;
import com.moon.moonagent.ai.agent.skill.AgentSkill;
import com.moon.moonagent.orchestration.CapabilityDescriptor;

public record SpecialistCapabilityAdapter(
        AgentSkill skill,
        SpecialistAgent specialist,
        CapabilityDescriptor descriptor
) implements AgentCapability {

    @Override
    public String key() {
        return descriptor.key();
    }

    @Override
    public SpecialistResult execute(SpecialistContext context) {
        return specialist.execute(context);
    }
}
