package com.moon.moonagent.service;

import org.springframework.web.multipart.MultipartFile;

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

    /** Infer Content-Type from filename extension. */
    static String contentType(String filename) {
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mov" -> "video/quicktime";
            case "wav" -> "audio/wav";
            case "mp3" -> "audio/mpeg";
            case "m4a" -> "audio/mp4";
            default -> "application/octet-stream";
        };
    }
}
