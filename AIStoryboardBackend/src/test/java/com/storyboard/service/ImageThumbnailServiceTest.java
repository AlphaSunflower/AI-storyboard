package com.storyboard.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ImageThumbnailServiceTest {

    @TempDir
    Path cacheDir;

    @Test
    void scalesWideImagesAndReusesTheDiskCache() throws Exception {
        ImageThumbnailService service = new ImageThumbnailService(cacheDir.toString());
        byte[] source = image(1200, 800, "jpeg");

        ImageThumbnailService.Thumb thumb = service.thumbnail("abc123.jpeg", source, 480);

        assertThat(thumb).isNotNull();
        assertThat(thumb.contentType()).isEqualTo("image/jpeg");
        assertThat(thumb.bytes().length).isLessThan(source.length);
        BufferedImage scaled = ImageIO.read(new ByteArrayInputStream(thumb.bytes()));
        assertThat(scaled.getWidth()).isEqualTo(480);
        assertThat(scaled.getHeight()).isEqualTo(320);

        // 文件名是内容哈希 + 宽度进 key：第二次必须命中同一份缓存字节
        ImageThumbnailService.Thumb again = service.thumbnail("abc123.jpeg", source, 480);
        assertThat(again).isNotNull();
        assertThat(again.bytes()).isEqualTo(thumb.bytes());
        assertThat(cacheDir.toFile().list()).isNotEmpty();
    }

    @Test
    void keepsSmallImagesAndNonRasterFormatsUntouched() throws Exception {
        ImageThumbnailService service = new ImageThumbnailService(cacheDir.toString());

        // 原图不比目标宽 → 不放大
        assertThat(service.thumbnail("small.png", image(320, 200, "png"), 480)).isNull();
        // SVG 不是位图、GIF 有动画 → 不缩
        assertThat(service.thumbnail("vector.svg", "<svg/>".getBytes(), 480)).isNull();
        assertThat(service.thumbnail("anim.gif", image(800, 600, "png"), 320)).isNull();
        // 没传宽度 → 走原图
        assertThat(service.thumbnail("wide.jpeg", image(1200, 800, "jpeg"), null)).isNull();
    }

    @Test
    void clampsRequestedWidthToSafeRange() throws Exception {
        ImageThumbnailService service = new ImageThumbnailService(cacheDir.toString());
        byte[] source = image(1600, 900, "jpeg");

        // 超上限按 1600 处理（等于原宽 → 不缩）；超下限按 48
        assertThat(service.thumbnail("wide.jpeg", source, 100_000)).isNull();
        ImageThumbnailService.Thumb tiny = service.thumbnail("wide.jpeg", source, 1);
        assertThat(tiny).isNotNull();
        assertThat(ImageIO.read(new ByteArrayInputStream(tiny.bytes())).getWidth()).isEqualTo(48);
    }

    private static byte[] image(int width, int height, String format) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(120, 90, 200));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }
}
