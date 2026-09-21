package com.storyboard.asset.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 启动后异步执行一次幂等旧资产迁移，不阻塞服务对外就绪。 */
@Component
@ConditionalOnProperty(
        name = "storyboard.asset.legacy-ai-migration-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class LegacyAiAssetMigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyAiAssetMigrationRunner.class);

    private final LegacyAiAssetMigrationService migrationService;

    public LegacyAiAssetMigrationRunner(LegacyAiAssetMigrationService migrationService) {
        this.migrationService = migrationService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        Thread.ofVirtual().name("legacy-ai-asset-migration").start(() -> {
            try {
                int count = migrationService.migrate(null);
                if (count > 0) log.info("旧 AI 资产迁移完成: {} 个", count);
            } catch (Exception e) {
                log.warn("旧 AI 资产迁移失败，可调用内部迁移接口重试: {}", e.getMessage());
            }
        });
    }
}
