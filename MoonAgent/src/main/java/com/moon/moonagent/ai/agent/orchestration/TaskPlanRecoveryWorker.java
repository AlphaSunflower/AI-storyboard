package com.moon.moonagent.ai.agent.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.ai.agent.recovery.AgentRecoverySource;
import com.moon.moonagent.ai.agent.recovery.RecoveryCandidate;
import com.moon.moonagent.ai.agent.recovery.RecoveryOutcome;
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
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class TaskPlanRecoveryWorker implements AgentRecoverySource {

    private static final Logger log = LoggerFactory.getLogger(TaskPlanRecoveryWorker.class);
    private static final String RESOURCE_TYPE = "task-plan";

    private final TaskPlanStore planStore;
    private final AgentConversationMapper conversationMapper;
    private final AgentAssetMapper assetMapper;
    private final TaskPlanScheduler scheduler;
    private final DynamicTaskEngine dynamicTaskEngine;

    @Override
    public String name() {
        return RESOURCE_TYPE;
    }

    @Override
    public Duration interval() {
        return Duration.ofSeconds(30);
    }

    @Override
    public int batchSize() {
        return 20;
    }

    @Override
    public List<RecoveryCandidate> findCandidates(OffsetDateTime now, int limit) {
        return planStore.findRecoverable(limit).stream()
                .map(plan -> new RecoveryCandidate(
                        RESOURCE_TYPE, plan.planId(), plan.conversationId(), Map.of()))
                .toList();
    }

    @Override
    public RecoveryOutcome recover(RecoveryCandidate candidate, int attempt, String leaseOwner) {
        TaskPlan plan = planStore.find(candidate.resourceId()).orElse(null);
        if (plan == null) return RecoveryOutcome.completed();
        AgentConversation conversation = conversationMapper.selectById(plan.conversationId());
        if (conversation == null) return RecoveryOutcome.terminal("conversation missing");
        return recover(conversation, plan);
    }

    @Override
    public RecoveryOutcome exhausted(RecoveryCandidate candidate, String error) {
        TaskPlan plan = planStore.find(candidate.resourceId()).orElse(null);
        if (plan == null) return RecoveryOutcome.terminal(error);
        TaskPlan failed = new TaskPlan(
                plan.planId(), plan.conversationId(), plan.goal(), TaskPlanState.FAILED,
                plan.version(), plan.maxNodes(), plan.maxAttemptsPerNode(), plan.maxReplans(),
                plan.replanCount(), plan.nodes(), plan.edges(),
                error == null ? "recovery attempts exhausted" : error, plan.planSignature());
        planStore.update(failed);
        return RecoveryOutcome.terminal(error);
    }

    public void recover() {
        List<TaskPlan> plans = planStore.findRecoverable(batchSize());
        for (TaskPlan plan : plans) {
            AgentConversation conversation = conversationMapper.selectById(plan.conversationId());
            if (conversation == null) continue;
            try {
                recover(conversation, plan);
            } catch (Exception e) {
                log.warn("task plan recovery failed: planId={}, error={}", plan.planId(), e.getMessage());
            }
        }
    }

    private RecoveryOutcome recover(AgentConversation conversation, TaskPlan plan) {
        try {
            if (plan.state() == TaskPlanState.WAITING_ASYNC) {
                return recoverWaitingAsync(conversation, plan);
            }
            TaskPlan recovered = scheduler.recoverInterrupted(plan);
            if (!recovered.equals(plan)) {
                recovered = planStore.update(recovered);
            }
            dynamicTaskEngine.resumePlan(conversation, recovered);
            return RecoveryOutcome.completed();
        } catch (Exception e) {
            return RecoveryOutcome.retryable(e.getMessage());
        }
    }

    private RecoveryOutcome recoverWaitingAsync(AgentConversation conversation, TaskPlan plan) {
        boolean waiting = false;
        for (TaskNode node : plan.nodes()) {
            if (node.state() != TaskNodeState.WAITING_ASYNC) continue;
            if (node.asyncTaskId() == null || node.asyncTaskId().isBlank()) continue;
            AgentAsset asset = assetMapper.selectOne(new LambdaQueryWrapper<AgentAsset>()
                    .eq(AgentAsset::getTaskId, node.asyncTaskId())
                    .eq(AgentAsset::getPlanId, plan.planId())
                    .eq(AgentAsset::getNodeId, node.nodeId())
                    .last("LIMIT 1"));
            if (asset == null || (!"completed".equals(asset.getStatus()) && !"failed".equals(asset.getStatus()))) {
                waiting = true;
                continue;
            }
            boolean success = "completed".equals(asset.getStatus());
            dynamicTaskEngine.continueAfterAsync(
                    conversation, plan.planId(), node.nodeId(), success,
                    success ? asset.getUrl() : asset.getError());
            return RecoveryOutcome.completed();
        }
        return waiting
                ? RecoveryOutcome.deferred("async task is still running")
                : RecoveryOutcome.terminal("waiting async node has no task id");
    }
}
