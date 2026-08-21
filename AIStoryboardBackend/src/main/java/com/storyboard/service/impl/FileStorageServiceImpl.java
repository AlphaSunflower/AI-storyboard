package com.storyboard.service.impl;

import com.storyboard.service.FileStorageService;
import com.storyboard.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

/**
 * 文件存储服务实现：图片/视频下载保存、上传图片保存、本地路径解析。
 */
@Service
public class FileStorageServiceImpl implements FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageServiceImpl.class);
    private static final Path IMAGES_DIR = Paths.get("uploads/images");
    private static final Path VIDEOS_DIR = Paths.get("uploads/videos");
    private static final Path AUDIOS_DIR = Paths.get("uploads/audios");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /** 构造时确保上传目录存在（无注入依赖；建目录属初始化副作用，保留显式无参构造器，非手写注入构造器例外） */
    public FileStorageServiceImpl() {
        try {
            Files.createDirectories(IMAGES_DIR);
            Files.createDirectories(VIDEOS_DIR);
            Files.createDirectories(AUDIOS_DIR);
        } catch (IOException e) {
            log.error("Failed to create upload directories", e);
        }
    }

    @Override
    public String saveImage(String sourceUrl) {
        return downloadAndSave(sourceUrl, IMAGES_DIR, "/api/files/images/", "image/png");
    }

    @Override
    public String saveImageFromBase64(String base64Data) {
        try {
            // Strip data URI prefix if present (e.g. "data:image/png;base64,")
            String clean = base64Data;
            if (clean.contains(",") && clean.contains("base64")) {
                clean = clean.substring(clean.indexOf(",") + 1);
            }
            byte[] bytes = Base64.getDecoder().decode(clean);
            String extension = "png"; // Gemini returns PNG by default
            String filename = UUID.randomUUID().toString() + "." + extension;
            Path target = IMAGES_DIR.resolve(filename);
            Files.write(target, bytes);
            log.debug("Saved base64 image: {}", target);
            return "/api/files/images/" + filename;
        } catch (IOException e) {
            throw new BusinessException(50201, "Failed to save base64 image: " + e.getMessage(), e);
        }
    }

    /** 允许保存的上传图片扩展名白名单（M6），其余一律回退 png */
    private static final Set<String> ALLOWED_IMAGE_EXTENSIONS =
        Set.of("png", "jpg", "jpeg", "webp", "gif");

    /** 允许保存的参考音频扩展名白名单 */
    private static final Set<String> ALLOWED_AUDIO_EXTENSIONS = Set.of("wav", "mp3", "m4a");

    /** 允许保存的参考视频扩展名白名单 */
    private static final Set<String> ALLOWED_VIDEO_EXTENSIONS = Set.of("mp4", "mov");

    @Override
    public String saveUploadedReference(String type, org.springframework.web.multipart.MultipartFile file) {
        try {
            if (file == null || file.isEmpty()) {
                throw new BusinessException(40001, "上传文件为空");
            }
            String t = type == null ? "image" : type;
            String contentType = file.getContentType();
            Path targetDir;
            String urlPrefix;
            Set<String> allowedExts;
            switch (t) {
                case "video" -> {
                    if (contentType == null || !(contentType.startsWith("video/") || contentType.contains("quicktime"))) {
                        throw new BusinessException(40001, "仅支持上传视频文件");
                    }
                    targetDir = VIDEOS_DIR; urlPrefix = "/api/files/videos/"; allowedExts = ALLOWED_VIDEO_EXTENSIONS;
                }
                case "audio" -> {
                    if (contentType == null || !contentType.startsWith("audio/")) {
                        throw new BusinessException(40001, "仅支持上传音频文件");
                    }
                    targetDir = AUDIOS_DIR; urlPrefix = "/api/files/audios/"; allowedExts = ALLOWED_AUDIO_EXTENSIONS;
                }
                default -> {
                    if (contentType == null || !contentType.startsWith("image/")) {
                        throw new BusinessException(40001, "仅支持上传图片文件");
                    }
                    targetDir = IMAGES_DIR; urlPrefix = "/api/files/images/"; allowedExts = ALLOWED_IMAGE_EXTENSIONS;
                }
            }
            String original = file.getOriginalFilename();
            String extension = switch (t) {
                case "video" -> "mp4";
                case "audio" -> "mp3";
                default -> "png";
            };
            if (original != null && original.contains(".")) {
                String raw = original.substring(original.lastIndexOf('.') + 1).toLowerCase();
                // 扩展名白名单校验，非法回退默认扩展名（防御异常扩展名）
                if (allowedExts.contains(raw)) extension = raw;
            }
            String filename = UUID.randomUUID().toString() + "." + extension;
            Files.write(targetDir.resolve(filename), file.getBytes());
            log.debug("Saved reference {}: {}", t, targetDir.resolve(filename));
            return urlPrefix + filename;
        } catch (IOException e) {
            throw new BusinessException(50201, "保存参考素材失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String saveUploadedImage(org.springframework.web.multipart.MultipartFile file) {
        try {
            if (file == null || file.isEmpty()) {
                throw new BusinessException(40001, "上传文件为空");
            }
            // 校验 content-type：仅接受图片（防改后缀上传 html/脚本，如 .png 实为 text/html）
            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                throw new BusinessException(40001, "仅支持上传图片文件");
            }
            String original = file.getOriginalFilename();
            String extension = "png";
            if (original != null && original.contains(".")) {
                String raw = original.substring(original.lastIndexOf('.') + 1).toLowerCase();
                // M6：扩展名白名单——仅接受常见图片格式，其余回退 png（防御异常扩展名）
                extension = ALLOWED_IMAGE_EXTENSIONS.contains(raw) ? raw : "png";
            }
            String filename = UUID.randomUUID().toString() + "." + extension;
            Path target = IMAGES_DIR.resolve(filename);
            Files.write(target, file.getBytes());
            log.debug("Saved uploaded image: {}", target);
            return "/api/files/images/" + filename;
        } catch (IOException e) {
            throw new BusinessException(50201, "保存上传图片失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String saveVideo(String sourceUrl) {
        // Handle non-http sources (e.g., raw base64 or data URI)
        if (sourceUrl == null || sourceUrl.isBlank()) {
            throw new BusinessException(40001, "Empty video URL");
        }
        if (sourceUrl.startsWith("http://") || sourceUrl.startsWith("https://")) {
            return downloadAndSave(sourceUrl, VIDEOS_DIR, "/api/files/videos/", "video/mp4");
        }
        // Might be a data URI or raw base64 — log and try direct URI as fallback
        log.warn("saveVideo received non-HTTP source (len={}), attempting direct URI", sourceUrl.length());
        return downloadAndSave(sourceUrl, VIDEOS_DIR, "/api/files/videos/", "video/mp4");
    }

    private String downloadAndSave(String sourceUrl, Path targetDir, String urlPrefix, String defaultContentType) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(sourceUrl))
                    .timeout(Duration.ofSeconds(120))
                    .GET()
                    .build();

            HttpResponse<InputStream> resp = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (resp.statusCode() != 200) {
                throw new BusinessException(50201, "Download failed, status: " + resp.statusCode());
            }

            String extension = extractExtension(sourceUrl, resp);
            String filename = UUID.randomUUID().toString() + "." + extension;
            Path target = targetDir.resolve(filename);
            Path tmp = targetDir.resolve(filename + ".tmp");

            try (InputStream in = resp.body()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);

            log.debug("Downloaded and saved: {} -> {}", sourceUrl, target);
            return urlPrefix + filename;
        } catch (IOException e) {
            throw new BusinessException(50201, "Failed to download file from " + sourceUrl + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50201, "Download interrupted: " + sourceUrl, e);
        }
    }

    private String extractExtension(String sourceUrl, HttpResponse<?> resp) {
        // Try from URL
        String path = sourceUrl;
        int qIdx = path.indexOf('?');
        if (qIdx > 0) path = path.substring(0, qIdx);

        String filename = path.substring(path.lastIndexOf('/') + 1);
        int dotIdx = filename.lastIndexOf('.');
        if (dotIdx > 0 && dotIdx < filename.length() - 1) {
            return filename.substring(dotIdx + 1).toLowerCase();
        }

        // Try from Content-Type header
        String contentType = resp.headers().firstValue("Content-Type").orElse("");
        if (contentType.contains("png")) return "png";
        if (contentType.contains("jpeg") || contentType.contains("jpg")) return "jpg";
        if (contentType.contains("webp")) return "webp";
        if (contentType.contains("mp4")) return "mp4";
        if (contentType.contains("gif")) return "gif";

        // Default
        return "png";
    }

    @Override
    public Path resolveImage(String filename) {
        return safeResolve(IMAGES_DIR, filename);
    }

    @Override
    public Path resolveVideo(String filename) {
        return safeResolve(VIDEOS_DIR, filename);
    }

    @Override
    public Path resolveAudio(String filename) {
        return safeResolve(AUDIOS_DIR, filename);
    }

    /** 路径穿越防护：resolve + normalize 后校验必须在目标目录下 */
    private Path safeResolve(Path dir, String filename) {
        Path resolved = dir.resolve(filename).normalize();
        if (!resolved.startsWith(dir.normalize())) {
            throw new BusinessException(40001, "非法文件路径");
        }
        return resolved;
    }
}
