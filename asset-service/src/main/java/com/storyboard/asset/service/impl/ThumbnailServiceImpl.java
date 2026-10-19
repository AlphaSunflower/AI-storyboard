package com.storyboard.asset.service.impl;

import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.security.FileAccessUrlSigner;
import com.storyboard.asset.service.ThumbnailService;
import com.storyboard.asset.storage.ObjectStorage;
import com.storyboard.asset.storage.StoredObject;
import com.storyboard.common.BusinessException;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
public class ThumbnailServiceImpl implements ThumbnailService {

    private static final int MIN_SIZE = 64;
    private static final int MAX_SIZE = 1024;
    private static final long MAX_SOURCE_BYTES = 40L * 1024 * 1024;

    private final ObjectStorage storage;
    private final FileAccessUrlSigner signer;
    private final LibraryRepository repository;

    public ThumbnailServiceImpl(ObjectStorage storage, FileAccessUrlSigner signer,
                                LibraryRepository repository) {
        this.storage = storage;
        this.signer = signer;
        this.repository = repository;
    }

    @Override
    public StoredObject open(String path, String token, int size) {
        try {
            signer.verify(path, token);
        } catch (IllegalArgumentException error) {
            throw new BusinessException(40301, error.getMessage());
        }
        String clean = path.split("\\?", 2)[0];
        String objectKey = clean.substring("/api/files/".length());
        if (objectKey.isBlank() || objectKey.contains("..")) {
            throw new BusinessException(40001, "文件路径不合法");
        }
        if (!isImage(objectKey)) {
            throw new BusinessException(40001, "只有图片支持缩略图");
        }
        int target = Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
        String thumbKey = "thumbnails/" + LibraryRepository.sha256Hex(
                (objectKey + ":" + target).getBytes(StandardCharsets.UTF_8)) + ".jpg";
        if (storage.exists(thumbKey)) {
            return storage.get(thumbKey, null);
        }
        try (StoredObject source = storage.get(objectKey, null)) {
            if (source.contentLength() > MAX_SOURCE_BYTES) {
                throw new BusinessException(40001, "图片过大，无法生成缩略图");
            }
            BufferedImage image = ImageIO.read(source.stream());
            if (image == null) {
                // WebP/SVG 等没有内置解码器：直接返回原图，前端仍能显示。
                return storage.get(objectKey, null);
            }
            repository.updateDominantColorByPath(clean,
                    com.storyboard.asset.service.DominantColorExtractor.hex(image));
            byte[] thumbnail = encodeJpeg(scale(image, target));
            storage.put(thumbKey, new ByteArrayInputStream(thumbnail), thumbnail.length, "image/jpeg");
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessException(50201, "生成缩略图失败: " + error.getMessage(), error);
        }
        return storage.get(thumbKey, null);
    }

    @Override
    public void analyzeColor(String path) {
        if (path == null || !path.startsWith("/api/files/")) return;
        String clean = path.split("\\?", 2)[0];
        String objectKey = clean.substring("/api/files/".length());
        if (objectKey.contains("..") || !isImage(objectKey)) return;
        try (StoredObject source = storage.get(objectKey, null)) {
            BufferedImage image = ImageIO.read(source.stream());
            String color = com.storyboard.asset.service.DominantColorExtractor.hex(image);
            repository.updateDominantColorByPath(clean, color);
        } catch (Exception ignored) {
            // 单张颜色分析失败不影响搜索/浏览。
        }
    }

    private static boolean isImage(String objectKey) {        String lower = objectKey.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp");
    }

    private static BufferedImage scale(BufferedImage source, int maxSize) {
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 0 || height <= 0) return source;
        double ratio = Math.min(1d, (double) maxSize / Math.max(width, height));
        int targetWidth = Math.max(1, (int) Math.round(width * ratio));
        int targetHeight = Math.max(1, (int) Math.round(height * ratio));
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static byte[] encodeJpeg(BufferedImage image) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam params = writer.getDefaultWriteParam();
        params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        params.setCompressionQuality(0.82f);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            writer.write(null, new IIOImage(image, null, null), params);
            writer.dispose();
            return output.toByteArray();
        }
    }
}
