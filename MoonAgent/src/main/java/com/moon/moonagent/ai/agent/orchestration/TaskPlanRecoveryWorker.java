package com.moon.moonagent.ai.agent.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.orchestration.TaskNode;
import com.moon.moonagent.orchestration.TaskNodeState;
import com.moon.moonagent.orchestration.TaskPlan;
import com.moon.moonagent.orchestration.TaskPlanScheduler;
import com.moon.moonagent.orchestration.TaskPlanState;
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
    private final AgentAssetMapper assetMapper;
    private final TaskPlanScheduler scheduler;
    private final DynamicTaskEngine dynamicTaskEngine;

    @Scheduled(fixedDelayString = "${ai.agent.dynamic-plan.recovery-delay-ms:30000}")
    public void recover() {
        List<TaskPlan> plans = planStore.findRecoverable(20);
        for (TaskPlan plan : plans) {
            AgentConversation conversation = conversationMapper.selectById(plan.conversationId());
            if (conversation == null) continue;
            try {
                if (plan.state() == TaskPlanState.WAITING_ASYNC) {
                    recoverWaitingAsync(conversation, plan);
                    continue;
                }
                TaskPlan recovered = scheduler.recoverInterrupted(plan);
                if (!recovered.equals(plan)) {
                    recovered = planStore.update(recovered);
                }
                dynamicTaskEngine.resumePlan(conversation, recovered);
            } catch (Exception e) {
                log.warn("task plan recovery failed: planId={}, error={}", plan.planId(), e.getMessage());
            }
        }
    }

    private void recoverWaitingAsync(AgentConversation conversation, TaskPlan plan) {
        for (TaskNode node : plan.nodes()) {
            if (node.state() != TaskNodeState.WAITING_ASYNC) continue;
            if (node.asyncTaskId() == null || node.asyncTaskId().isBlank()) continue;
            AgentAsset asset = assetMapper.selectOne(new LambdaQueryWrapper<AgentAsset>()
                    .eq(AgentAsset::getTaskId, node.asyncTaskId())
                    .eq(AgentAsset::getPlanId, plan.planId())
                    .eq(AgentAsset::getNodeId, node.nodeId())
                    .last("LIMIT 1"));
            if (asset == null || (!"completed".equals(asset.getStatus()) && !"failed".equals(asset.getStatus()))) {
                continue;
            }
            boolean success = "completed".equals(asset.getStatus());
            dynamicTaskEngine.continueAfterAsync(
                    conversation, plan.planId(), node.nodeId(), success,
                    success ? asset.getUrl() : asset.getError());
        }
    }
}
