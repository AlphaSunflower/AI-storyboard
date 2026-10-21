package com.moon.moonagent.ai.agent.archive;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 已归档会话清理的定时入口（每天 03:40，避开 checkpoint / 工具审计的 03:00、03:30）。
 *
 * <p>真正的清理口径见 {@link ArchivedConversationCleanupService}：只清运行时派生数据，
 * <b>不删 MinIO 图片/文档对象</b>。单飞锁防止手动触发与定时触发叠加。
 */
@Component
@RequiredArgsConstructor
public class ArchivedConversationCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ArchivedConversationCleanupJob.class);

    private final ArchivedConversationCleanupService cleanupService;
    private final ArchiveCleanupProperties properties;
    private final AtomicBoolean running = new AtomicBoolean();

    @Scheduled(cron = "${ai.cleanup.archive.cron:0 40 3 * * *}")
    public void scheduled() {
        runOnce();
    }

    /** 手动触发入口（排障/补跑）；返回清理的会话数。 */
    public int runOnce() {
        if (!properties.isEnabled()) return 0;
        if (!running.compareAndSet(false, true)) {
            log.info("已归档会话清理已有任务在执行，跳过本次触发");
            return 0;
        }
        try {
            long startedAt = System.currentTimeMillis();
            int cleaned = cleanupService.runOnce();
            if (cleaned > 0) {
                log.info("已归档会话清理结束：{} 个会话，耗时 {} ms",
                        cleaned, System.currentTimeMillis() - startedAt);
            }
            return cleaned;
        } finally {
            running.set(false);
        }
    }
}
