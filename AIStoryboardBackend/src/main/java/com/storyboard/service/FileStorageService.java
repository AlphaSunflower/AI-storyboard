package com.storyboard.service;

import java.nio.file.Path;

/**
 * 文件存储服务接口：图片/视频下载保存、上传图片保存、本地路径解析、Content-Type 推断。
 */
public interface FileStorageService {

    /**
     * Download image from URL and save locally.
     * @return local relative path like /api/files/images/xxx.png
     */
    String saveImage(String sourceUrl);

    /**
     * Save image from base64 data (Gemini inlineData).
     * @return local relative path like /api/files/images/xxx.png
     */
    String saveImageFromBase64(String base64Data);

    /**
     * 保存用户上传的图片文件（Agent 对话参考图）。
     * @return local relative path like /api/files/images/xxx.png
     */
    String saveUploadedImage(org.springframework.web.multipart.MultipartFile file);

    /**
     * Download video from URL and save locally.
     * @return local relative path like /api/files/videos/xxx.mp4
     */
    String saveVideo(String sourceUrl);

    /** Resolve local file path from the URL prefix path. */
    Path resolveImage(String filename);

    /** 解析视频本地文件路径。 */
    Path resolveVideo(String filename);

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
            default -> "application/octet-stream";
        };
    }
}
