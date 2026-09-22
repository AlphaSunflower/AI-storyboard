package com.moon.moonagent.ai.agent.orchestration;

import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.orchestration.TaskPlan;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class TaskPlanRecoveryWorker {

    private static final Logger log = LoggerFactory.getLogger(TaskPlanRecoveryWorker.class);

    private final TaskPlanStore planStore;
    private final AgentConversationMapper conversationMapper;
    private final DynamicTaskEngine dynamicTaskEngine;

    @Scheduled(fixedDelayString = "${ai.agent.dynamic-plan.recovery-delay-ms:30000}")
    public void recover() {
        List<TaskPlan> plans = planStore.findRecoverable(20);
        for (TaskPlan plan : plans) {
            AgentConversation conversation = conversationMapper.selectById(plan.conversationId());
            if (conversation == null) continue;
            try {
                dynamicTaskEngine.resumePlan(conversation, plan);
            } catch (Exception e) {
                log.warn("task plan recovery failed: planId={}, error={}", plan.planId(), e.getMessage());
            }
        }
    }
}
