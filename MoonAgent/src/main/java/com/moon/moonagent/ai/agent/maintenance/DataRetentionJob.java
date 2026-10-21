package com.moon.moonagent.ai.agent.maintenance;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 数据保留期清理的定时入口（每天 04:20，排在归档清理 03:40 之后）。
 *
 * <p>默认 {@code ai.cleanup.retention.dry-run=true}：只统计并打日志。
 * 观察日志里的行数确认无误后，置 {@code AI_RETENTION_DRY_RUN=false} 才会真正删除。
 */
@Component
@RequiredArgsConstructor
public class DataRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionJob.class);

    private final DataRetentionService retentionService;
    private final RetentionProperties properties;
    private final AtomicBoolean running = new AtomicBoolean();

    @Scheduled(cron = "${ai.cleanup.retention.cron:0 20 4 * * *}")
    public void scheduled() {
        runOnce();
    }

    /** 手动触发入口（排障/补跑）；返回实际删除的行数。 */
    public int runOnce() {
        if (!properties.isEnabled()) return 0;
        if (!running.compareAndSet(false, true)) {
            log.info("数据保留清理已有任务在执行，跳过本次触发");
            return 0;
        }
        try {
            long startedAt = System.currentTimeMillis();
            int deleted = retentionService.runOnce();
            if (deleted > 0) {
                log.info("数据保留清理结束：删除 {} 行，耗时 {} ms",
                        deleted, System.currentTimeMillis() - startedAt);
            }
            return deleted;
        } finally {
            running.set(false);
        }
    }
}
