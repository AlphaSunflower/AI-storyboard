package com.moon.moonagent.ai.agent.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ToolInvocationRetentionJob {

    private final ToolInvocationLedger ledger;
    private final ToolAuditProperties properties;

    @Scheduled(cron = "${ai.agent.tool-audit.cleanup-cron:0 30 3 * * *}")
    public void cleanup() {
        if (!properties.isEnabled()) return;
        int total = 0;
        for (int round = 0; round < 20; round++) {
            int removed = ledger.cleanup(properties.getCleanupBatchSize());
            total += removed;
            if (removed < properties.getCleanupBatchSize()) break;
        }
        if (total > 0) log.info("tool invocation audit cleanup removed={}", total);
    }
}
