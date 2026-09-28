package com.storyboard.asset.worker;

import com.storyboard.asset.cleanup.ObjectReferenceGuard;
import com.storyboard.asset.config.StorageProperties;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 主动扫描 MinIO 中 images/、videos/ 前缀的对象，把超出宽限期且无任何引用的对象写入既有清理队列。
 * fail-safe：列举或引用判定任一环节失败时整轮跳过，不产生任何新清理任务。
 */
@Component
public class StorageOrphanScanner {

    private static final Logger log = LoggerFactory.getLogger(StorageOrphanScanner.class);
    static final List<String> SCAN_PREFIXES = List.of("images/", "videos/");
    static final int PAGE_SIZE = 500;

    private final ObjectStorage storage;
    private final ObjectReferenceGuard guard;
    private final LibraryRepository repository;
    private final StorageProperties properties;

    public StorageOrphanScanner(
            ObjectStorage storage,
            ObjectReferenceGuard guard,
            LibraryRepository repository,
            StorageProperties properties
    ) {
        this.storage = storage;
        this.guard = guard;
        this.repository = repository;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "#{${storyboard.storage.orphan-scan-interval-hours:6} * 3600000}")
    public void run() {
        scanOnce();
    }

    /** 扫描一轮：先收集全部候选，再一次性判定引用，最后才入队，保证任一步失败都不产生新任务。 */
    public void scanOnce() {
        if (!properties.orphanScanEnabled()) return;
        try {
            List<ObjectStorage.ObjectSummary> candidates = collectCandidates();
            if (candidates.isEmpty()) return;

            Set<String> keys = new LinkedHashSet<>();
            for (ObjectStorage.ObjectSummary object : candidates) keys.add(object.key());
            Set<String> referenced = guard.referencedObjectKeys(keys);

            int queued = 0;
            for (ObjectStorage.ObjectSummary object : candidates) {
                if (referenced.contains(object.key())) continue;
                repository.createCleanupTask(object.key());
                queued++;
            }
            log.info("MinIO 孤儿扫描完成: candidates={}, protected={}, queued={}",
                    keys.size(), referenced.size(), queued);
        } catch (Exception e) {
            log.warn("MinIO 孤儿扫描整轮跳过（fail-safe）: {}", e.getMessage(), e);
        }
    }

    private List<ObjectStorage.ObjectSummary> collectCandidates() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(properties.orphanScanGraceHours()));
        List<ObjectStorage.ObjectSummary> candidates = new ArrayList<>();
        for (String prefix : SCAN_PREFIXES) {
            String token = null;
            do {
                ObjectStorage.ObjectPage page = storage.listObjects(prefix, token, PAGE_SIZE);
                for (ObjectStorage.ObjectSummary object : page.objects()) {
                    if (object.lastModified() == null || object.lastModified().isAfter(cutoff)) continue;
                    candidates.add(object);
                }
                token = page.nextContinuationToken();
            } while (token != null && !token.isBlank());
        }
        return candidates;
    }
}