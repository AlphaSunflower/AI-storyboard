package com.moon.moonagent.ai.agent.recovery;

import com.moon.moonagent.ai.agent.agentscope.AgentScopeAsyncTaskService;
import com.moon.moonagent.ai.agent.agentscope.AgentScopeMediaRuntime;
import com.moon.moonagent.ai.agent.orchestration.TaskPlanRecoveryWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Temporary rollback path. Only active when the recovery coordinator is
 * explicitly disabled through {@code ai.agent.recovery.mode=legacy}.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.agent.recovery", name = "mode",
        havingValue = "legacy")
public class LegacyRecoveryScheduler {

    private final TaskPlanRecoveryWorker taskPlanRecovery;
    private final ObjectProvider<AgentScopeAsyncTaskService> asyncTaskRecovery;
    private final ObjectProvider<AgentScopeMediaRuntime> videoRecovery;

    @Scheduled(fixedDelayString = "${ai.agent.dynamic-plan.recovery-delay-ms:30000}")
    void recoverTaskPlans() {
        taskPlanRecovery.recover();
    }

    @Scheduled(fixedDelayString = "${ai.agent.agentscope.async-recovery-delay-ms:30000}",
            initialDelayString = "${ai.agent.agentscope.async-recovery-initial-delay-ms:15000}")
    void recoverBatchImages() {
        AgentScopeAsyncTaskService service = asyncTaskRecovery.getIfAvailable();
        if (service == null) return;
        for (RecoveryCandidate candidate :
                service.findCandidates(OffsetDateTime.now(), service.batchSize())) {
            service.recover(candidate, 1, "legacy-batch-image-recovery");
        }
    }

    @Scheduled(fixedDelayString = "${ai.agent.agentscope.video-recovery-delay-ms:30000}",
            initialDelayString = "${ai.agent.agentscope.video-recovery-initial-delay-ms:15000}")
    void recoverVideoTasks() {
        AgentScopeMediaRuntime service = videoRecovery.getIfAvailable();
        if (service == null) return;
        for (RecoveryCandidate candidate :
                service.findCandidates(OffsetDateTime.now(), service.batchSize())) {
            service.recover(candidate, 1, "legacy-video-recovery");
        }
    }
}
