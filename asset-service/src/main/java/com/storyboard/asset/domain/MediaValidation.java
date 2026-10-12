package com.storyboard.asset.domain;

import com.storyboard.common.BusinessException;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 上传文件类型、大小和相对路径校验。
 *
 * <p>项目库支持任意文件：图片/视频/音频保留魔数校验，文档与「其他」类型只校验大小。
 */
public final class MediaValidation {

    public static final long IMAGE_MAX_BYTES = 30L * 1024 * 1024;
    public static final long VIDEO_MAX_BYTES = 500L * 1024 * 1024;
    public static final long AUDIO_MAX_BYTES = 100L * 1024 * 1024;
    /** 文档与「其他」类型的单文件上限。 */
    public static final long ATTACHMENT_MAX_BYTES = 20L * 1024 * 1024;

    private static final Map<String, String> EXTENSIONS = Map.ofEntries(
            Map.entry("image/png", "png"), Map.entry("image/jpeg", "jpg"),
            Map.entry("image/webp", "webp"), Map.entry("image/gif", "gif"),
            Map.entry("video/mp4", "mp4"), Map.entry("video/quicktime", "mov"),
            Map.entry("audio/mpeg", "mp3"), Map.entry("audio/wav", "wav"), Map.entry("audio/mp4", "m4a")
    );

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp", "gif");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "mov", "webm", "mkv");
    private static final Set<String> AUDIO_EXTENSIONS = Set.of("mp3", "wav", "m4a", "aac", "ogg", "flac");

    /** 常见文档 MIME，命中即归入 DOCUMENT。 */
    private static final Set<String> DOCUMENT_MIME_TYPES = Set.of(
            "application/pdf",
            "application/json",
            "application/xml",
            "application/yaml",
            "application/rtf",
            "text/rtf",
            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.presentation",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/epub+zip"
    );

    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "tsv",
            "md", "markdown", "mdown", "mkd", "txt", "text", "rtf", "odt", "ods", "odp",
            "json", "jsonl", "ndjson", "yaml", "yml", "xml", "html", "htm", "log",
            "tex", "rst", "sql", "srt", "vtt", "epub"
    );

    private static final Pattern SAFE_EXTENSION = Pattern.compile("[a-z0-9]{1,16}");

    private MediaValidation() {
    }

    /**
     * 按 MIME 与文件扩展名归类为 IMAGE/VIDEO/AUDIO/DOCUMENT/OTHER，永不抛异常。
     *
     * <p>浏览器对 .md、.zip 等类型常给出空或泛化 MIME，因此扩展名作为兜底信号。
     */
    public static String mediaType(String mimeType, String fileName) {
        String mime = normalizeMime(mimeType);
        String extension = extensionOf(fileName);
        if (mime.startsWith("image/")) return "IMAGE";
        if (mime.startsWith("video/")) return "VIDEO";
        if (mime.startsWith("audio/")) return "AUDIO";
        if (IMAGE_EXTENSIONS.contains(extension)) return "IMAGE";
        if (VIDEO_EXTENSIONS.contains(extension)) return "VIDEO";
        if (AUDIO_EXTENSIONS.contains(extension)) return "AUDIO";
        if (DOCUMENT_EXTENSIONS.contains(extension) || DOCUMENT_MIME_TYPES.contains(mime)) return "DOCUMENT";
        return "OTHER";
    }

    public static String mediaTypeFromObjectKey(String objectKey) {
        if (objectKey == null) throw new BusinessException(40001, "对象 key 不能为空");
        if (objectKey.startsWith("images/")) return "IMAGE";
        if (objectKey.startsWith("videos/")) return "VIDEO";
        if (objectKey.startsWith("audios/")) return "AUDIO";
        if (objectKey.startsWith("documents/")) return "DOCUMENT";
        if (objectKey.startsWith("files/")) return "OTHER";
        throw new BusinessException(40001, "无法从对象 key 判断媒体类型");
    }

    public static String extension(String mimeType, String fileName) {
        String fromMime = EXTENSIONS.get(normalizeMime(mimeType));
        if (fromMime != null) return fromMime;
        String extension = extensionOf(fileName);
        if ("jpeg".equals(extension)) return "jpg";
        return SAFE_EXTENSION.matcher(extension).matches() ? extension : "bin";
    }

    /** 浏览器对未知扩展名常给出空 Content-Type，这里按类型与文件名补齐。 */
    public static String normalizeContentType(String contentType, String mediaType, String fileName) {
        String normalized = contentType == null ? "" : contentType.trim();
        if (!normalized.isEmpty()) return normalized;
        return defaultMimeType(mediaType, fileName);
    }

    /** 依据对象键扩展名推断 MIME；无法识别时按媒体类型回退。 */
    public static String defaultMimeType(String mediaType, String objectKey) {
        switch (extensionOf(objectKey)) {
            case "png": return "image/png";
            case "jpg", "jpeg": return "image/jpeg";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            case "svg": return "image/svg+xml";
            case "mp4": return "video/mp4";
            case "mov": return "video/quicktime";
            case "webm": return "video/webm";
            case "mp3": return "audio/mpeg";
            case "wav": return "audio/wav";
            case "m4a": return "audio/mp4";
            case "pdf": return "application/pdf";
            case "doc": return "application/msword";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls": return "application/vnd.ms-excel";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt": return "application/vnd.ms-powerpoint";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "csv": return "text/csv";
            case "md", "markdown", "mdown", "mkd": return "text/markdown";
            case "json", "jsonl", "ndjson": return "application/json";
            case "html", "htm": return "text/html";
            case "xml": return "application/xml";
            case "yaml", "yml": return "application/yaml";
            case "txt", "text", "log": return "text/plain";
            default: break;
        }
        String type = mediaType == null ? "" : mediaType.toUpperCase(Locale.ROOT);
        return switch (type) {
            case "IMAGE" -> "image/png";
            case "VIDEO" -> "video/mp4";
            case "AUDIO" -> "audio/mpeg";
            default -> "application/octet-stream";
        };
    }

    public static void validateSize(String mediaType, long sizeBytes) {
        long max
                = switch (mediaType == null ? "" : mediaType.toUpperCase(Locale.ROOT)) {
            case "IMAGE" -> IMAGE_MAX_BYTES;
            case "VIDEO" -> VIDEO_MAX_BYTES;
            case "AUDIO" -> AUDIO_MAX_BYTES;
            case "DOCUMENT", "OTHER" -> ATTACHMENT_MAX_BYTES;
            default -> throw new BusinessException(40001, "不支持的媒体类型");
        };
        if (sizeBytes < 0 || sizeBytes > max) {
            throw new BusinessException(40001, "文件超过大小上限 " + (max / 1024 / 1024) + "MB");
        }
    }

    public static void validateMagic(String mediaType, byte[] bytes) {
        String type = mediaType == null ? "" : mediaType.toUpperCase(Locale.ROOT);
        if ("DOCUMENT".equals(type) || "OTHER".equals(type)) return;
        if (bytes == null || bytes.length < 12) throw new BusinessException(40001, "文件内容不完整");
        boolean matches = switch (type) {
            case "IMAGE" -> isPng(bytes) || isJpeg(bytes) || isGif(bytes) || isWebp(bytes);
            case "VIDEO" -> isIsoBmff(bytes) || isWebm(bytes);
            case "AUDIO" -> isMp3(bytes) || isWav(bytes) || isIsoBmff(bytes);
            default -> false;
        };
        if (!matches) throw new BusinessException(40001, "文件内容与声明类型不匹配");
    }

    private static String normalizeMime(String mimeType) {
        return mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT).split(";", 1)[0].trim();
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) return "";
        String clean = fileName.replace('\\', '/');
        int slash = clean.lastIndexOf('/');
        String name = slash >= 0 ? clean.substring(slash + 1) : clean;
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean isPng(byte[] b) {
        return b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 0x50 && b[2] == 0x4e && b[3] == 0x47;
    }

    private static boolean isJpeg(byte[] b) {
        return (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8;
    }

    private static boolean isGif(byte[] b) {
        return b[0] == 'G' && b[1] == 'I' && b[2] == 'F';
    }

    private static boolean isWebp(byte[] b) {
        return b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }

    private static boolean isIsoBmff(byte[] b) {
        return b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p';
    }

    private static boolean isWebm(byte[] b) {
        return (b[0] & 0xff) == 0x1a && (b[1] & 0xff) == 0x45
                && (b[2] & 0xff) == 0xdf && (b[3] & 0xff) == 0xa3;
    }

    private static boolean isMp3(byte[] b) {
        return (b[0] == 'I' && b[1] == 'D' && b[2] == '3')
                || ((b[0] & 0xff) == 0xff && (b[1] & 0xe0) == 0xe0);
    }

    private static boolean isWav(byte[] b) {
        return b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'A' && b[10] == 'V' && b[11] == 'E';
    }

    public static String cleanRelativePath(String path) {
        if (path == null || path.isBlank()) return null;
        String cleaned = path.replace('\\', '/')
                .replaceAll("[\\p{Cntrl}]", "")
                .replaceAll("/{2,}", "/")
                .replaceAll("(^|/)\\.\\.(/|$)", "/");
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        if (cleaned.isBlank() || cleaned.contains("../") || cleaned.length() > 1024) {
            throw new BusinessException(40001, "相对路径不合法");
        }
        return cleaned;
    }
}
