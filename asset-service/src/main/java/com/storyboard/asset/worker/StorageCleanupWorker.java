package com.storyboard.asset.worker;

import com.storyboard.asset.cleanup.ObjectReferenceGuard;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class StorageCleanupWorker {

    private static final Logger log = LoggerFactory.getLogger(StorageCleanupWorker.class);

    private final LibraryRepository repository;
    private final ObjectStorage storage;
    private final ObjectReferenceGuard objectReferenceGuard;
    private final String workerId = "asset-cleanup-" + UUID.randomUUID();

    public StorageCleanupWorker(
            LibraryRepository repository,
            ObjectStorage storage,
            ObjectReferenceGuard objectReferenceGuard
    ) {
        this.repository = repository;
        this.storage = storage;
        this.objectReferenceGuard = objectReferenceGuard;
    }

    @Scheduled(fixedDelay = 30_000)
    public void run() {
        for (LibraryRepository.CleanupTask task : repository.claimCleanupTasks(workerId, 20)) {
            try {
                if (objectReferenceGuard.isReferenced(task.objectKey())) {
                    repository.completeCleanup(task.id());
                    continue;
                }
                if (storage.exists(task.objectKey())) storage.delete(task.objectKey());
                repository.completeCleanup(task.id());
            } catch (Exception e) {
                repository.failCleanup(task.id(), task.attemptCount(), e.getMessage());
                log.warn("MinIO 清理失败: taskId={}, key={}, attempt={}, error={}",
                        task.id(), task.objectKey(), task.attemptCount(), e.getMessage());
            }
        }
    }
}