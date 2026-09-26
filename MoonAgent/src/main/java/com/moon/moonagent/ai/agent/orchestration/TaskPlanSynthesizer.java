package com.moon.moonagent.ai.agent.orchestration;

import com.moon.moonagent.orchestration.CapabilityDescriptor;
import com.moon.moonagent.orchestration.TaskPlanDraft;

import java.util.List;
import java.util.Map;

public interface TaskPlanSynthesizer {

    TaskPlanDraft synthesize(TaskPlanningRequest request,
                             Map<String, CapabilityDescriptor> capabilities,
                             List<String> validationErrors);

    /** 带本轮 usage 采集的动态计划合成；旧实现可不覆盖。 */
    default TaskPlanDraft synthesize(TaskPlanningRequest request,
                                     Map<String, CapabilityDescriptor> capabilities,
                                     List<String> validationErrors,
                                     com.moon.moonagent.ai.agent.handler.AgentTraceAccumulator trace) {
        return synthesize(request, capabilities, validationErrors);
    }
}
