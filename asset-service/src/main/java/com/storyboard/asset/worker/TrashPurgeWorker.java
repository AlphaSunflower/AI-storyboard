package com.storyboard.asset.worker;

import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.service.AssetPurgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回收站到期清理：领取 purge_after <= now() 的 TRASHED 资产，走与手动 purge 相同的 AssetPurgeService。
 * 仍有活跃引用时置 PURGE_BLOCKED，对象保留。
 */
@Component
public class TrashPurgeWorker {

    private static final Logger log = LoggerFactory.getLogger(TrashPurgeWorker.class);
    static final int BATCH_SIZE = 50;

    private final LibraryRepository repository;
    private final AssetPurgeService purgeService;

    public TrashPurgeWorker(LibraryRepository repository, AssetPurgeService purgeService) {
        this.repository = repository;
        this.purgeService = purgeService;
    }

    @Scheduled(fixedDelay = 300_000)
    @Transactional
    public void run() {
        for (String assetId : repository.claimDueTrashAssets(BATCH_SIZE)) {
            try {
                AssetPurgeService.PurgeOutcome outcome = purgeService.purgeDue(assetId);
                if (outcome == AssetPurgeService.PurgeOutcome.BLOCKED) {
                    log.info("回收站资产到期但仍有引用，保留对象: assetId={}", assetId);
                }
            } catch (Exception e) {
                log.warn("回收站到期清理失败: assetId={}, error={}", assetId, e.getMessage());
            }
        }
    }
}