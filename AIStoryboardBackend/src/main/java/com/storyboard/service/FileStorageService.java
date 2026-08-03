package com.storyboard.service;

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

@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
    private static final Path IMAGES_DIR = Paths.get("uploads/images");
    private static final Path VIDEOS_DIR = Paths.get("uploads/videos");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public FileStorageService() {
        try {
            Files.createDirectories(IMAGES_DIR);
            Files.createDirectories(VIDEOS_DIR);
        } catch (IOException e) {
            log.error("Failed to create upload directories", e);
        }
    }

    /**
     * Download image from URL and save locally.
     * @return local relative path like /api/files/images/xxx.png
     */
    public String saveImage(String sourceUrl) {
        return downloadAndSave(sourceUrl, IMAGES_DIR, "/api/files/images/", "image/png");
    }

    /**
     * Save image from base64 data (Gemini inlineData).
     * @return local relative path like /api/files/images/xxx.png
     */
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
            log.info("Saved base64 image: {}", target);
            return "/api/files/images/" + filename;
        } catch (IOException e) {
            throw new RuntimeException("Failed to save base64 image: " + e.getMessage(), e);
        }
    }

    /** 允许保存的上传图片扩展名白名单（M6），其余一律回退 png */
    private static final Set<String> ALLOWED_IMAGE_EXTENSIONS =
        Set.of("png", "jpg", "jpeg", "webp", "gif");

    /**
     * 保存用户上传的图片文件（Agent 对话参考图）。
     * @return local relative path like /api/files/images/xxx.png
     */
    public String saveUploadedImage(org.springframework.web.multipart.MultipartFile file) {
        try {
            if (file == null || file.isEmpty()) {
                throw new RuntimeException("上传文件为空");
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
            log.info("Saved uploaded image: {}", target);
            return "/api/files/images/" + filename;
        } catch (IOException e) {
            throw new RuntimeException("保存上传图片失败: " + e.getMessage(), e);
        }
    }

    /**
     * Download video from URL and save locally.
     * @return local relative path like /api/files/videos/xxx.mp4
     */
    public String saveVideo(String sourceUrl) {
        // Handle non-http sources (e.g., raw base64 or data URI)
        if (sourceUrl == null || sourceUrl.isBlank()) {
            throw new RuntimeException("Empty video URL");
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
                throw new RuntimeException("Download failed, status: " + resp.statusCode());
            }

            String extension = extractExtension(sourceUrl, resp);
            String filename = UUID.randomUUID().toString() + "." + extension;
            Path target = targetDir.resolve(filename);

            try (InputStream in = resp.body()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }

            log.info("Downloaded and saved: {} -> {}", sourceUrl, target);
            return urlPrefix + filename;
        } catch (IOException e) {
            throw new RuntimeException("Failed to download file from " + sourceUrl + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Download interrupted: " + sourceUrl, e);
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

    /** Resolve local file path from the URL prefix path. */
    public Path resolveImage(String filename) {
        return IMAGES_DIR.resolve(filename);
    }

    public Path resolveVideo(String filename) {
        return VIDEOS_DIR.resolve(filename);
    }

    /** Infer Content-Type from filename extension. */
    public static String contentType(String filename) {
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mov" -> "video/quicktime";
            default -> "application/octet-stream";
        };
    }
}
