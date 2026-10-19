package com.storyboard.asset.worker;

import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.service.ThumbnailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 后台补齐历史素材主色，供颜色搜索使用；每次少量、失败跳过。 */
@Component
public class LibraryColorBackfillWorker {

    private static final Logger log = LoggerFactory.getLogger(LibraryColorBackfillWorker.class);
    private static final int BATCH_SIZE = 100;

    private final LibraryRepository repository;
    private final ThumbnailService thumbnailService;

    public LibraryColorBackfillWorker(LibraryRepository repository, ThumbnailService thumbnailService) {
        this.repository = repository;
        this.thumbnailService = thumbnailService;
    }

    @Scheduled(initialDelayString = "#{${storyboard.search.color-backfill-initial-delay-ms:30000}}",
            fixedDelayString = "#{${storyboard.search.color-backfill-interval-ms:600000}}")
    public void run() {
        try {
            for (String path : repository.findAssetsMissingColor(BATCH_SIZE)) {
                thumbnailService.analyzeColor(path);
            }
        } catch (Exception error) {
            log.warn("素材主色补齐失败：{}", error.getMessage());
        }
    }
}
