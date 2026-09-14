package com.storyboard.asset.domain;

import com.storyboard.common.BusinessException;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 上传文件类型、大小和相对路径校验。 */
public final class MediaValidation {

    public static final long IMAGE_MAX_BYTES = 30L * 1024 * 1024;
    public static final long VIDEO_MAX_BYTES = 500L * 1024 * 1024;
    public static final long AUDIO_MAX_BYTES = 100L * 1024 * 1024;

    private static final Map<String, String> EXTENSIONS = Map.ofEntries(
            Map.entry("image/png", "png"), Map.entry("image/jpeg", "jpg"),
            Map.entry("image/webp", "webp"), Map.entry("image/gif", "gif"),
            Map.entry("video/mp4", "mp4"), Map.entry("video/quicktime", "mov"),
            Map.entry("audio/mpeg", "mp3"), Map.entry("audio/wav", "wav"), Map.entry("audio/mp4", "m4a")
    );

    private MediaValidation() {
    }

    public static String mediaType(String mimeType) {
        if (mimeType == null) throw new BusinessException(40001, "文件类型不能为空");
        if (mimeType.startsWith("image/")) return "IMAGE";
        if (mimeType.startsWith("video/")) return "VIDEO";
        if (mimeType.startsWith("audio/")) return "AUDIO";
        throw new BusinessException(40001, "仅支持图片、视频或音频文件");
    }

    public static String mediaTypeFromObjectKey(String objectKey) {
        if (objectKey == null) throw new BusinessException(40001, "对象 key 不能为空");
        if (objectKey.startsWith("images/")) return "IMAGE";
        if (objectKey.startsWith("videos/")) return "VIDEO";
        if (objectKey.startsWith("audios/")) return "AUDIO";
        throw new BusinessException(40001, "无法从对象 key 判断媒体类型");
    }

    public static String extension(String mimeType, String fileName) {
        String normalized = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        String fromMime = EXTENSIONS.get(normalized);
        if (fromMime != null) return fromMime;
        if (fileName != null && fileName.contains(".")) {
            String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (Set.of("png", "jpg", "jpeg", "webp", "gif", "mp4", "mov", "mp3", "wav", "m4a").contains(extension)) {
                return "jpeg".equals(extension) ? "jpg" : extension;
            }
        }
        throw new BusinessException(40001, "不支持的文件扩展名");
    }

    public static void validateSize(String mediaType, long sizeBytes) {
        long max = switch (mediaType) {
            case "IMAGE" -> IMAGE_MAX_BYTES;
            case "VIDEO" -> VIDEO_MAX_BYTES;
            case "AUDIO" -> AUDIO_MAX_BYTES;
            default -> throw new BusinessException(40001, "不支持的媒体类型");
        };
        if (sizeBytes < 0 || sizeBytes > max) {
            throw new BusinessException(40001, "文件超过大小上限 " + (max / 1024 / 1024) + "MB");
        }
    }

    public static void validateMagic(String mediaType, byte[] bytes) {
        if (bytes == null || bytes.length < 12) throw new BusinessException(40001, "文件内容不完整");
        boolean matches = switch (mediaType) {
            case "IMAGE" -> isPng(bytes) || isJpeg(bytes) || isGif(bytes) || isWebp(bytes);
            case "VIDEO" -> isIsoBmff(bytes) || isWebm(bytes);
            case "AUDIO" -> isMp3(bytes) || isWav(bytes) || isIsoBmff(bytes);
            default -> false;
        };
        if (!matches) throw new BusinessException(40001, "文件内容与声明类型不匹配");
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
