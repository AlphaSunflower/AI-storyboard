package com.moon.moonagent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.moon.moonagent.entity.StorageCleanupTask;
import com.moon.moonagent.mapper.StorageCleanupTaskMapper;
import com.moon.moonagent.service.FileStorageService;
import com.moon.moonagent.service.StorageCleanupService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StorageCleanupServiceImpl implements StorageCleanupService {

    private static final Logger log = LoggerFactory.getLogger(StorageCleanupServiceImpl.class);

    private final StorageCleanupTaskMapper mapper;
    private final FileStorageService fileStorageService;

    @Override
    public void enqueue(String objectUrl) {
        if (objectUrl == null || objectUrl.isBlank()) return;
        try {
            StorageCleanupTask task = new StorageCleanupTask();
            task.setObjectUrl(objectUrl.trim());
            task.setStatus("pending");
            task.setAttempts(0);
            task.setNextAttemptAt(OffsetDateTime.now());
            task.setCreatedAt(OffsetDateTime.now());
            task.setUpdatedAt(OffsetDateTime.now());
            mapper.insert(task);
        } catch (Exception error) {
            log.warn("enqueue storage cleanup failed objectUrl={} error={}", objectUrl, error.getMessage());
        }
    }

    @Override
    public void enqueueAll(Collection<String> objectUrls) {
        if (objectUrls == null) return;
        objectUrls.stream().filter(url -> url != null && !url.isBlank()).distinct().forEach(this::enqueue);
    }

    @Scheduled(fixedDelayString = "${storyboard.storage.cleanup-delay-ms:30000}")
    public void drain() {
        List<StorageCleanupTask> tasks = mapper.selectList(new LambdaQueryWrapper<StorageCleanupTask>()
                .eq(StorageCleanupTask::getStatus, "pending")
                .le(StorageCleanupTask::getNextAttemptAt, OffsetDateTime.now())
                .orderByAsc(StorageCleanupTask::getCreatedAt)
                .last("LIMIT 50"));
        for (StorageCleanupTask task : tasks) {
            try {
                deleteObject(task.getObjectUrl());
                task.setStatus("completed");
                task.setLastError(null);
            } catch (Exception error) {
                int attempts = task.getAttempts() == null ? 0 : task.getAttempts();
                task.setAttempts(attempts + 1);
                task.setLastError(error.getMessage());
                task.setStatus(attempts >= 7 ? "failed" : "pending");
                task.setNextAttemptAt(OffsetDateTime.now().plusMinutes(Math.min(60, 1L << attempts)));
            }
            task.setUpdatedAt(OffsetDateTime.now());
            mapper.updateById(task);
        }
    }

    private void deleteObject(String objectUrl) {
        String path;
        try {
            URI uri = URI.create(objectUrl);
            path = uri.getPath() == null ? objectUrl : uri.getPath();
        } catch (Exception ignored) {
            path = objectUrl;
        }
        int slash = path.lastIndexOf('/');
        if (slash <= 0 || slash >= path.length() - 1) throw new IllegalArgumentException("invalid object url");
        String filename = path.substring(slash + 1);
        if (path.startsWith("/api/files/images/")) fileStorageService.deleteImage(filename);
        else if (path.startsWith("/api/files/videos/")) fileStorageService.deleteVideo(filename);
        else if (path.startsWith("/api/files/audios/")) fileStorageService.deleteAudio(filename);
        else if (path.startsWith("/api/files/files/")) fileStorageService.deleteFile(filename);
        else throw new IllegalArgumentException("unsupported object url");
    }
}
