package com.moon.moonagent.ai.agent.orchestration;

import com.moon.moonagent.orchestration.CapabilityDescriptor;
import com.moon.moonagent.orchestration.TaskPlanDraft;

import java.util.List;
import java.util.Map;

public interface TaskPlanSynthesizer {

    TaskPlanDraft synthesize(TaskPlanningRequest request,
                             Map<String, CapabilityDescriptor> capabilities,
                             List<String> validationErrors);
}
