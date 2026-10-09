package com.moon.moonagent.service.impl;

import com.moon.moonagent.service.FileStorageService;
import com.moon.moonagent.security.FileUrlSigner;
import com.moon.moonagent.security.OutboundUrlGuard;
import com.moon.moonagent.storage.MinioStorage;
import com.storyboard.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件存储服务实现：图片/视频下载保存、上传图片保存、对象读取/删除（MinIO 对象存储）。
 * 保存后返回带 HMAC 签名的对外路径（/api/files/{type}/xxx?token=...），读取返回对象字节。
 */
@Service
@RequiredArgsConstructor
public class FileStorageServiceImpl implements FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageServiceImpl.class);

    private final MinioStorage minioStorage;
    private final FileUrlSigner fileUrlSigner;
    private final ConcurrentHashMap<String, CompletableFuture<String>> downloadInflight =
            new ConcurrentHashMap<>();

    @Autowired(required = false)
    @Qualifier("attachmentHttpClient")
    private HttpClient httpClient;

    /** 允许保存的上传图片扩展名白名单（其余回退 png，防御异常扩展名） */
    private static final Set<String> ALLOWED_IMAGE_EXTENSIONS =
        Set.of("png", "jpg", "jpeg", "webp", "gif");
    /** 允许保存的参考音频扩展名白名单 */
    private static final Set<String> ALLOWED_AUDIO_EXTENSIONS = Set.of("wav", "mp3", "m4a");
    /** 允许保存的参考视频扩展名白名单 */
    private static final Set<String> ALLOWED_VIDEO_EXTENSIONS = Set.of("mp4", "mov");

    @Override
    public String saveImage(String sourceUrl) {
        return downloadAndSave(sourceUrl, "images", "/api/files/images/");
    }

    @Override
    public String saveImageFromBase64(String base64Data) {
        String clean = base64Data;
        if (clean.contains(",") && clean.contains("base64")) {
            clean = clean.substring(clean.indexOf(",") + 1);
        }
        byte[] bytes = Base64.getDecoder().decode(clean);
        String filename = UUID.randomUUID() + ".png";
        minioStorage.put(minioStorage.key("images", filename), bytes, "image/png");
        log.debug("Saved base64 image to MinIO: {}", filename);
        return fileUrlSigner.signIfLocal("/api/files/images/" + filename);
    }

    @Override
    public String saveUploadedReference(String type, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(40001, "上传文件为空");
        }
        String t = type == null ? "image" : type;
        String contentType = file.getContentType();
        String targetType;
        String urlPrefix;
        Set<String> allowedExts;
        switch (t) {
            case "video" -> {
                if (contentType == null || !(contentType.startsWith("video/") || contentType.contains("quicktime"))) {
                    throw new BusinessException(40001, "仅支持上传视频文件");
                }
                targetType = "videos"; urlPrefix = "/api/files/videos/"; allowedExts = ALLOWED_VIDEO_EXTENSIONS;
            }
            case "audio" -> {
                if (contentType == null || !contentType.startsWith("audio/")) {
                    throw new BusinessException(40001, "仅支持上传音频文件");
                }
                targetType = "audios"; urlPrefix = "/api/files/audios/"; allowedExts = ALLOWED_AUDIO_EXTENSIONS;
            }
            case "file" -> {
                targetType = "files"; urlPrefix = "/api/files/files/"; allowedExts = Set.of();
            }
            default -> {
                if (contentType == null || !contentType.startsWith("image/")) {
                    throw new BusinessException(40001, "仅支持上传图片文件");
                }
                targetType = "images"; urlPrefix = "/api/files/images/"; allowedExts = ALLOWED_IMAGE_EXTENSIONS;
            }
        }
        String extension = switch (t) {
            case "video" -> "mp4";
            case "audio" -> "mp3";
            case "file" -> "bin";
            default -> "png";
        };
        String original = file.getOriginalFilename();
        if (original != null && original.contains(".")) {
            String raw = original.substring(original.lastIndexOf('.') + 1).toLowerCase();
            if ("file".equals(t)) {
                if (raw.matches("[a-z0-9]{1,12}")) extension = raw;
            } else if (allowedExts.contains(raw)) {
                extension = raw;
            }
        }
        try {
            byte[] bytes = file.getBytes();
            String filename = UUID.randomUUID() + "." + extension;
            minioStorage.put(minioStorage.key(targetType, filename), bytes, contentType(filename));
            log.debug("Saved reference {} to MinIO: {}", t, filename);
            return fileUrlSigner.signIfLocal(urlPrefix + filename);
        } catch (IOException e) {
            throw new BusinessException(50201, "保存参考素材失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String saveUploadedImage(MultipartFile file) {
        try {
            if (file == null || file.isEmpty()) {
                throw new BusinessException(40001, "上传文件为空");
            }
            // 校验 content-type：仅接受图片（防改后缀上传 html/脚本，如 .png 实为 text/html）
            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                throw new BusinessException(40001, "仅支持上传图片文件");
            }
            String extension = "png";
            String original = file.getOriginalFilename();
            if (original != null && original.contains(".")) {
                String raw = original.substring(original.lastIndexOf('.') + 1).toLowerCase();
                extension = ALLOWED_IMAGE_EXTENSIONS.contains(raw) ? raw : "png";
            }
            byte[] bytes = file.getBytes();
            String filename = UUID.randomUUID() + "." + extension;
            minioStorage.put(minioStorage.key("images", filename), bytes, contentType(filename));
            log.debug("Saved uploaded image to MinIO: {}", filename);
            return fileUrlSigner.signIfLocal("/api/files/images/" + filename);
        } catch (IOException e) {
            throw new BusinessException(50201, "保存上传图片失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String saveVideo(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()) {
            throw new BusinessException(40001, "Empty video URL");
        }
        if (sourceUrl.startsWith("http://") || sourceUrl.startsWith("https://")) {
            return downloadAndSave(sourceUrl, "videos", "/api/files/videos/");
        }
        // data URI / raw base64：直接转存
        log.warn("saveVideo received non-HTTP source (len={}), attempting base64 decode", sourceUrl.length());
        String clean = sourceUrl;
        if (clean.contains(",") && clean.contains("base64")) {
            clean = clean.substring(clean.indexOf(",") + 1);
        }
        byte[] bytes = Base64.getDecoder().decode(clean);
        String filename = UUID.randomUUID() + ".mp4";
        minioStorage.put(minioStorage.key("videos", filename), bytes, "video/mp4");
        return fileUrlSigner.signIfLocal("/api/files/videos/" + filename);
    }

    @Override
    public String saveVideoBytes(byte[] bytes, String extension) {
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(40001, "视频内容为空");
        }
        String ext = extension == null || !extension.matches("[A-Za-z0-9]{1,8}")
                ? "mp4" : extension.toLowerCase();
        String filename = UUID.randomUUID() + "." + ext;
        minioStorage.put(minioStorage.key("videos", filename), bytes, contentType(filename));
        return fileUrlSigner.signIfLocal("/api/files/videos/" + filename);
    }

    private String downloadAndSave(String sourceUrl, String type, String urlPrefix) {
        String normalized = sourceUrl == null ? "" : sourceUrl.trim();
        String hash = sha256(normalized);
        String extension = extractExtension(normalized, "images".equals(type) ? "png" : "mp4");
        String filename = hash + "." + extension;
        String key = minioStorage.key(type, filename);
        if (minioStorage.exists(key)) {
            return fileUrlSigner.signIfLocal(urlPrefix + filename);
        }
        CompletableFuture<String> created = new CompletableFuture<>();
        CompletableFuture<String> existing = downloadInflight.putIfAbsent(normalized, created);
        if (existing != null) return existing.join();
        try {
            String result = downloadAndSaveUncached(normalized, type, urlPrefix, filename, key);
            created.complete(result);
            return result;
        } catch (RuntimeException error) {
            created.completeExceptionally(error);
            throw error;
        } finally {
            downloadInflight.remove(normalized, created);
        }
    }

    private String downloadAndSaveUncached(String sourceUrl, String type, String urlPrefix,
                                           String filename, String key) {
        try {
            OutboundUrlGuard.requireSafeHttpUrl(sourceUrl);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(sourceUrl))
                    .timeout(Duration.ofSeconds(120))
                    .GET()
                    .build();
            HttpResponse<InputStream> resp = httpClient().send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                throw new BusinessException(50201, "Download failed, status: " + resp.statusCode());
            }
            long maxBytes = "images".equals(type) ? 20L * 1024 * 1024 : 200L * 1024 * 1024;
            long contentLength = resp.headers().firstValueAsLong("content-length").orElse(-1L);
            if (contentLength > maxBytes) {
                throw new BusinessException(50201,
                        "Downloaded file exceeds limit: " + contentLength + " bytes");
            }
            String remoteType = resp.headers().firstValue("content-type").orElse("");
            if ("images".equals(type) && !remoteType.isBlank()
                    && !remoteType.toLowerCase().startsWith("image/")) {
                throw new BusinessException(50201, "Remote resource is not an image: " + remoteType);
            }
            byte[] bytes;
            try (InputStream in = resp.body()) {
                bytes = in.readAllBytes();
            }
            if (bytes.length > maxBytes) {
                throw new BusinessException(50201, "Downloaded file exceeds limit: " + bytes.length);
            }
            minioStorage.put(key, bytes, contentType(filename));
            log.debug("Downloaded and saved to MinIO: {} -> {}/{}", sourceUrl, type, filename);
            return fileUrlSigner.signIfLocal(urlPrefix + filename);
        } catch (IOException e) {
            throw new BusinessException(50201, "Failed to download file from " + sourceUrl + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50201, "Download interrupted: " + sourceUrl, e);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    /** 从 URL 提取扩展名（含 query/fragment 剥离）；失败回退默认值。 */
    private String extractExtension(String sourceUrl, String fallback) {
        String path = sourceUrl;
        int q = path.indexOf('?');
        if (q > 0) path = path.substring(0, q);
        int h = path.indexOf('#');
        if (h > 0) path = path.substring(0, h);
        String filename = path.substring(path.lastIndexOf('/') + 1);
        int dot = filename.lastIndexOf('.');
        if (dot > 0 && dot < filename.length() - 1) {
            return filename.substring(dot + 1).toLowerCase();
        }
        return fallback;
    }

    private String contentType(String filename) {
        return FileStorageService.contentType(filename);
    }

    @Override
    public byte[] readImage(String filename) {
        return minioStorage.get(minioStorage.key("images", filename));
    }

    @Override
    public byte[] readVideo(String filename) {
        return minioStorage.get(minioStorage.key("videos", filename));
    }

    @Override
    public byte[] readAudio(String filename) {
        return minioStorage.get(minioStorage.key("audios", filename));
    }

    @Override
    public byte[] readFile(String filename) {
        return minioStorage.get(minioStorage.key("files", filename));
    }

    @Override
    public void deleteImage(String filename) {
        minioStorage.delete(minioStorage.key("images", filename));
    }

    @Override
    public void deleteVideo(String filename) {
        minioStorage.delete(minioStorage.key("videos", filename));
    }

    @Override
    public void deleteAudio(String filename) {
        minioStorage.delete(minioStorage.key("audios", filename));
    }

    @Override
    public void deleteFile(String filename) {
        minioStorage.delete(minioStorage.key("files", filename));
    }

    @Override
    public String toImageDataUri(String urlOrPath) {
        String filename = extractFilename(urlOrPath);
        byte[] bytes = readImage(filename);
        if (bytes == null) {
            throw new BusinessException(40401, "资源不存在: " + filename);
        }
        String mime = FileStorageService.contentType(filename);
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    /** 从 URL 路径提取文件名（/api/files/images/abc.png → abc.png；含 query/fragment 剥离） */
    private String extractFilename(String urlPath) {
        if (urlPath == null || urlPath.isBlank()) return "";
        String p = urlPath;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        int h = p.indexOf('#');
        if (h >= 0) p = p.substring(0, h);
        int idx = p.lastIndexOf('/');
        return idx >= 0 ? p.substring(idx + 1) : p;
    }

    private HttpClient httpClient() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        }
        return httpClient;
    }
}
