package com.storyboard.asset.service;

import com.storyboard.asset.client.AssetContracts.MediaRef;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.common.BusinessException;
import org.springframework.stereotype.Service;

/**
 * 手动彻底删除与回收站到期自动清理共用的清理编排。
 * 只把对象写入既有清理队列，真正的 MinIO 删除仍由 StorageCleanupWorker 执行。
 */
@Service
public class AssetPurgeService {

    private final LibraryRepository repository;

    public AssetPurgeService(LibraryRepository repository) {
        this.repository = repository;
    }

    /** 手动彻底删除：先解除项目关联，再判定剩余活跃引用；仍有引用则阻塞并抛 40901。 */
    public void purgeManually(String assetId) {
        repository.unlinkAllProjects(assetId);
        if (repository.hasActiveReferences(assetId)) {
            repository.markPurgeBlocked(assetId);
            throw new BusinessException(40901, "资产仍被项目引用，已转为待处理状态");
        }
        if (!repository.markPurgePending(assetId)) {
            throw new BusinessException(40901, "清理状态已变化");
        }
        enqueueAndMarkPurged(assetId);
    }

    /** 回收站到期自动清理：不解除关联；仍有活跃引用则阻塞并保留对象。 */
    public PurgeOutcome purgeDue(String assetId) {
        if (repository.hasActiveReferences(assetId)) {
            repository.markPurgeBlocked(assetId);
            return PurgeOutcome.BLOCKED;
        }
        if (!repository.markPurgePending(assetId)) return PurgeOutcome.SKIPPED;
        enqueueAndMarkPurged(assetId);
        return PurgeOutcome.PURGED;
    }

    private void enqueueAndMarkPurged(String assetId) {
        for (MediaRef media : repository.listMedia(assetId)) {
            repository.createCleanupTask(media.objectKey());
        }
        repository.markPurged(assetId);
    }

    public enum PurgeOutcome {
        PURGED, BLOCKED, SKIPPED
    }
}