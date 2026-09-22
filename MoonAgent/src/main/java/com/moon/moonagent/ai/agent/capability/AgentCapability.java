package com.moon.moonagent.ai.agent.capability;

import com.moon.moonagent.ai.agent.specialist.SpecialistContext;
import com.moon.moonagent.ai.agent.specialist.SpecialistResult;
import com.moon.moonagent.orchestration.CapabilityDescriptor;

public interface AgentCapability {

    String key();

    CapabilityDescriptor descriptor();

    SpecialistResult execute(SpecialistContext context);
}
