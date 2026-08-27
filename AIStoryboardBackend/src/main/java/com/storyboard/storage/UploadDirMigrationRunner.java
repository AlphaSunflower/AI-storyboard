package com.storyboard.storage;

import com.storyboard.ai.AiConfigProperties;
import com.storyboard.service.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 存量迁移：把本地 uploads/{images,videos,audios}/ 下的现有文件一次性写入 MinIO（幂等）。
 * <p>
 * - 由 {@code storyboard.storage.migrate-upload-dir} 控制（默认 true），迁移完成可设 false 关闭。
 * - 幂等：已存在的 object key 跳过，重复启动不重复上传。
 * - 失败仅告警不阻断（残留文件后续可再跑迁移）。
 */
@Component
public class UploadDirMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UploadDirMigrationRunner.class);

    private final AiConfigProperties config;
    private final MinioStorage minioStorage;

    @Value("${storyboard.storage.migrate-upload-dir:true}")
    private boolean migrate;

    public UploadDirMigrationRunner(AiConfigProperties config, MinioStorage minioStorage) {
        this.config = config;
        this.minioStorage = minioStorage;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!migrate) {
            log.info("已关闭存量 uploads 迁移（storyboard.storage.migrate-upload-dir=false）");
            return;
        }
        migrateDir(config.getImageUploadDir(), "images");
        migrateDir(config.getVideoUploadDir(), "videos");
        migrateDir(config.getAudioUploadDir(), "audios");
    }

    private void migrateDir(String dir, String type) {
        Path p = Paths.get(dir);
        if (!Files.isDirectory(p)) {
            log.debug("迁移目录不存在，跳过: {}", p);
            return;
        }
        try (var stream = Files.list(p)) {
            List<Path> files = stream.filter(Files::isRegularFile).toList();
            int added = 0;
            for (Path f : files) {
                String filename = f.getFileName().toString();
                String key = minioStorage.key(type, filename);
                if (minioStorage.exists(key)) continue;
                byte[] bytes = Files.readAllBytes(f);
                minioStorage.put(key, bytes, FileStorageService.contentType(filename));
                added++;
            }
            log.info("存量迁移 {} -> MinIO: 新增 {} 个对象（共 {} 个文件）", dir, added, files.size());
        } catch (IOException e) {
            log.warn("迁移目录失败(忽略): {}, err={}", dir, e.getMessage());
        }
    }
}
