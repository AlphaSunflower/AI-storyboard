package com.storyboard.ai;

import com.moon.moonagent.service.FileStorageService;
import com.storyboard.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;

/**
 * agent-core 存储端口适配器：复用主后端既有的 MinIO 与文件签名实现。
 */
@Component
@RequiredArgsConstructor
public class AgentCoreFileStorageAdapter implements FileStorageService {

    /** 主后端存储实现，类型与 agent-core 端口同名但包不同。 */
    private final com.storyboard.service.FileStorageService delegate;

    @Override
    public String saveImage(String sourceUrl) {
        return delegate.saveImage(sourceUrl);
    }

    @Override
    public String saveImageFromBase64(String base64Data) {
        return delegate.saveImageFromBase64(base64Data);
    }

    @Override
    public String saveUploadedImage(MultipartFile file) {
        return delegate.saveUploadedImage(file);
    }

    @Override
    public String saveUploadedReference(String type, MultipartFile file) {
        return delegate.saveUploadedReference(type, file);
    }

    @Override
    public String saveVideo(String sourceUrl) {
        return delegate.saveVideo(sourceUrl);
    }

    @Override
    public String saveVideoBytes(byte[] bytes, String extension) {
        return delegate.saveVideoBytes(bytes, extension);
    }

    @Override
    public String saveGeneratedFile(byte[] bytes, String filename, String contentType) {
        return delegate.saveGeneratedFile(bytes, filename, contentType);
    }

    @Override
    public byte[] readImage(String filename) {
        return delegate.readImage(filename);
    }

    @Override
    public byte[] readVideo(String filename) {
        return delegate.readVideo(filename);
    }

    @Override
    public byte[] readAudio(String filename) {
        return delegate.readAudio(filename);
    }

    @Override
    public byte[] readFile(String filename) {
        return delegate.readFile(filename);
    }

    @Override
    public void deleteImage(String filename) {
        delegate.deleteImage(filename);
    }

    @Override
    public void deleteVideo(String filename) {
        delegate.deleteVideo(filename);
    }

    @Override
    public void deleteAudio(String filename) {
        delegate.deleteAudio(filename);
    }

    @Override
    public void deleteFile(String filename) {
        delegate.deleteFile(filename);
    }

    @Override
    public String toImageDataUri(String urlOrPath) {
        String filename = extractFilename(urlOrPath);
        byte[] bytes = delegate.readImage(filename);
        if (bytes == null) {
            throw new BusinessException(40401, "资源不存在: " + filename);
        }
        return "data:" + com.storyboard.service.FileStorageService.contentType(filename)
                + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static String extractFilename(String urlPath) {
        if (urlPath == null || urlPath.isBlank()) return "";
        String clean = urlPath;
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        int fragment = clean.indexOf('#');
        if (fragment >= 0) clean = clean.substring(0, fragment);
        int slash = clean.lastIndexOf('/');
        return slash >= 0 ? clean.substring(slash + 1) : clean;
    }
}
