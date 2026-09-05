package com.llmgateway.job;

import com.llmgateway.config.GatewayConfig;
import com.llmgateway.service.CallLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * call_log 冷热归档任务：每天凌晨把早于保留期的行分批迁移到 call_log_archive。
 * 保留期取自 sys_config `gateway.call-log-retention-days`（GatewayConfig 启动时加载，重启生效）；
 * 分批执行（batchSize=2000）+ 退出重试，避免大事务锁表阻塞异步落库。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CallLogArchiverJob {

    /** 单批迁移行数（避免长事务/大锁）。 */
    private static final int BATCH_SIZE = 2000;
    /** 批次间退避（毫秒），降低磁盘/lock 压力。 */
    private static final long BACKOFF_MS = 200;
    /** 单次触发最多批次数（防止后台异常导致无限循环）。 */
    private static final int MAX_BATCHES = 100;
    /** 单飞锁：同一时刻只跑一次归档，防止 @Scheduled 重入/手动触发叠加。 */
    private static final java.util.concurrent.locks.ReentrantLock HOLDER =
            new java.util.concurrent.locks.ReentrantLock();

    private final CallLogService callLogService;
    private final GatewayConfig gatewayConfig;

    @Scheduled(cron = "0 30 3 * * *")
    public void archiveExpired() {
        int days = gatewayConfig.getCallLogRetentionDays();
        if (days <= 0) {
            log.warn("call_log 保留天数非法（{}），跳过归档", days);
            return;
        }
        if (!HOLDER.tryLock()) {
            log.info("call_log 归档已有任务在执行，跳过本次触发");
            return;
        }
        try {
            int total = 0;
            for (int i = 0; i < MAX_BATCHES; i++) {
                int moved = callLogService.archiveOlderThan(days, BATCH_SIZE);
                total += moved;
                if (moved == 0) break;
                if (moved < BATCH_SIZE) break;
                sleep(BACKOFF_MS);
            }
            log.info("call_log 归档完成：保留 {} 天，迁移 {} 行", days, total);
        } catch (Exception e) {
            log.error("call_log 归档失败: {}", e.getMessage(), e);
        } finally {
            HOLDER.unlock();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
