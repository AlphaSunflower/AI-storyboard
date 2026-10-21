package com.storyboard.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

/**
 * 聊天/画布里按需生成的图片缩略图。
 *
 * <p>生成图原图约 1 MB（1024~2048px），聊天里只按 220px 高显示。原来的做法是原图直出：
 * 一条会话 4 张图就是 4.5 MB，切会话/滚动时解码还要抢主线程。这里按 {@code ?w=} 现缩，
 * 结果落本地磁盘缓存（文件名是内容哈希，天然不可变，缓存永远不会脏）。
 *
 * <p>只在「原图确实比目标宽」时缩；SVG / GIF（有动画）不缩，直接回退原图，
 * 让调用方继续走原来的直出路径。
 */
@Slf4j
@Service
public class ImageThumbnailService {

    /** 目标宽度下限：再小就看不清了。 */
    private static final int MIN_WIDTH = 48;
    /** 目标宽度上限：防止有人拿 ?w=100000 让服务端分配巨型位图。 */
    private static final int MAX_WIDTH = 1600;
    /** 原图字节上限：超过就不缩，避免把超大文件整个读进堆。 */
    private static final long MAX_SOURCE_BYTES = 24L * 1024 * 1024;
    private static final float JPEG_QUALITY = 0.82f;
    /** 这些格式不缩：SVG 不是位图，GIF 缩了会丢帧。 */
    private static final List<String> SKIP_EXTENSIONS = List.of("svg", "gif", "avif", "webp");

    private final Path cacheDir;

    public ImageThumbnailService(
            @Value("${files.thumbnail-dir:${java.io.tmpdir}/storyboard-thumbnails}") String directory) {
        this.cacheDir = Paths.get(directory);
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            log.warn("缩略图缓存目录不可用，将退化为每次现缩: dir={}, error={}", cacheDir, e.getMessage());
        }
    }

    /** 缩略图结果；{@code null} 表示这张图不缩（调用方回退原图）。 */
    public record Thumb(byte[] bytes, String contentType) {
    }

    public Thumb thumbnail(String filename, byte[] source, Integer requestedWidth) {
        if (requestedWidth == null || source == null || source.length == 0) return null;
        if (source.length > MAX_SOURCE_BYTES) return null;
        String ext = extensionOf(filename);
        if (SKIP_EXTENSIONS.contains(ext)) return null;

        int width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, requestedWidth));
        String cacheKey = cacheKey(filename, width);
        Thumb cached = readCache(cacheKey);
        if (cached != null) return cached;

        BufferedImage src;
        try {
            src = ImageIO.read(new ByteArrayInputStream(source));
        } catch (IOException e) {
            return null;
        }
        if (src == null || src.getWidth() <= width) return null;

        int targetHeight = Math.max(1, (int) Math.round(src.getHeight() * (width / (double) src.getWidth())));
        boolean alpha = src.getColorModel().hasAlpha();
        BufferedImage scaled = new BufferedImage(
                width, targetHeight, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            if (alpha) graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(src, 0, 0, width, targetHeight, null);
        } finally {
            graphics.dispose();
        }

        String outExt = alpha ? "png" : "jpg";
        byte[] bytes;
        try {
            bytes = alpha ? writePng(scaled) : writeJpeg(scaled);
        } catch (IOException e) {
            log.debug("缩略图编码失败，回退原图: file={}, error={}", filename, e.getMessage());
            return null;
        }
        writeCache(cacheKey + "." + outExt, bytes);
        return new Thumb(bytes, alpha ? "image/png" : "image/jpeg");
    }

    /** 文件名是内容哈希，宽度也进了 key：同名同宽永远命中同一份缓存。 */
    private static String cacheKey(String filename, int width) {
        String base = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return base + "@" + width;
    }

    private Thumb readCache(String key) {
        for (String ext : new String[]{"jpg", "png"}) {
            Path file = cacheDir.resolve(key + "." + ext);
            if (!Files.isRegularFile(file)) continue;
            try {
                return new Thumb(Files.readAllBytes(file), "jpg".equals(ext) ? "image/jpeg" : "image/png");
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    private void writeCache(String name, byte[] bytes) {
        try {
            Files.write(cacheDir.resolve(name), bytes);
        } catch (IOException e) {
            log.debug("缩略图写缓存失败（不影响响应）: name={}, error={}", name, e.getMessage());
        }
    }

    private static byte[] writeJpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static byte[] writePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static String extensionOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
