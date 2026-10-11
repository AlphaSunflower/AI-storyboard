package com.moon.moonagent.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Set;

/**
 * 文件存储服务接口：图片/视频下载保存、上传图片保存、对象读取/删除、Content-Type 推断、图片转 data URI。
 * <p>
 * 存储后端已切换 MinIO（对象存储），保存方法仍返回对外访问的相对路径（如 {@code /api/files/images/xxx.png}），
 * 读取方法返回对象字节（不存在返回 null）。
 */
public interface FileStorageService {

    /**
     * Download image from URL and save to MinIO.
     * @return relative path like /api/files/images/xxx.png
     */
    String saveImage(String sourceUrl);

    /**
     * Save image from base64 data (Gemini inlineData).
     * @return relative path like /api/files/images/xxx.png
     */
    String saveImageFromBase64(String base64Data);

    /**
     * 保存用户上传的图片文件（Agent 对话参考图）。
     * @return relative path like /api/files/images/xxx.png
     */
    String saveUploadedImage(MultipartFile file);

    /**
     * 保存对话/分镜附件（image→images，video→videos，audio→audios，其他→files）。
     * @return relative path like /api/files/images|videos|audios|files/xxx
     */
    String saveUploadedReference(String type, MultipartFile file);

    /**
     * Download video from URL and save to MinIO.
     * @return relative path like /api/files/videos/xxx.mp4
     */
    String saveVideo(String sourceUrl);

    /** Save generated or merged video bytes directly to MinIO. */
    String saveVideoBytes(byte[] bytes, String extension);

    /** Save a generated document or image using its original filename and MIME type. */
    String saveGeneratedFile(byte[] bytes, String filename, String contentType);

    /** 读取图片字节；MinIO 对象不存在返回 null。 */
    byte[] readImage(String filename);

    /** 读取视频字节；MinIO 对象不存在返回 null。 */
    byte[] readVideo(String filename);

    /** 读取音频字节；MinIO 对象不存在返回 null。 */
    byte[] readAudio(String filename);

    /** 读取通用文件字节；MinIO 对象不存在返回 null。 */
    byte[] readFile(String filename);

    /** 删除图片对象（best-effort：不存在/失败不抛）。 */
    void deleteImage(String filename);

    /** 删除视频对象（best-effort：不存在/失败不抛）。 */
    void deleteVideo(String filename);

    /** 删除音频对象（best-effort：不存在/失败不抛）。 */
    void deleteAudio(String filename);

    /** 删除通用文件对象（best-effort）。 */
    void deleteFile(String filename);

    /**
     * 读取对象图并转 base64 data URI（多模态调用用）。
     * @param urlOrPath 相对路径或 /api/files/images/xxx.png（兼容完整 URL / query / fragment）
     * @return "data:image/xxx;base64,...."
     */
    String toImageDataUri(String urlOrPath);

    /** Human-readable text/code extensions so agent-written scripts keep a useful MIME type. */
    Set<String> PLAIN_TEXT_EXTENSIONS = Set.of(
            "txt", "log", "html", "htm",
            "py", "pyw", "js", "mjs", "cjs", "jsx", "ts", "tsx",
            "java", "kt", "kts", "go", "rs", "rb", "php",
            "c", "h", "cc", "cpp", "cxx", "hpp", "hxx", "cs",
            "swift", "m", "mm", "scala", "dart", "lua", "pl", "pm", "r",
            "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd", "sql",
            "vue", "svelte", "astro", "toml", "gradle", "ini", "conf", "cfg",
            "config", "properties", "env", "makefile", "dockerfile", "gitignore",
            "editorconfig", "tex", "rst", "proto", "graphql", "gql");

    /** Infer Content-Type from filename extension. */
    static String contentType(String filename) {
        String ext = extensionOf(filename);
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "avif" -> "image/avif";
            case "svg" -> "image/svg+xml";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mov" -> "video/quicktime";
            case "mkv" -> "video/x-matroska";
            case "wav" -> "audio/wav";
            case "mp3" -> "audio/mpeg";
            case "m4a" -> "audio/mp4";
            case "ogg" -> "audio/ogg";
            case "flac" -> "audio/flac";
            case "md", "markdown" -> "text/markdown";
            case "csv" -> "text/csv";
            case "tsv" -> "text/tab-separated-values";
            case "json" -> "application/json";
            case "jsonl", "ndjson" -> "application/x-ndjson";
            case "yaml", "yml" -> "application/yaml";
            case "xml" -> "text/plain";
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "zip" -> "application/zip";
            case "tar" -> "application/x-tar";
            case "gz", "tgz" -> "application/gzip";
            case "bz2" -> "application/x-bzip2";
            case "xz" -> "application/x-xz";
            case "7z" -> "application/x-7z-compressed";
            case "rar" -> "application/vnd.rar";
            default -> PLAIN_TEXT_EXTENSIONS.contains(ext)
                    ? "text/plain" : "application/octet-stream";
        };
    }

    static String extensionOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
