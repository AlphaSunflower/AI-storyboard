package com.storyboard.asset.service;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/** 从图片中提取主色：5 bit/通道量化后取最高频桶的平均值。 */
public final class DominantColorExtractor {

    private DominantColorExtractor() {
    }

    public static String hex(BufferedImage image) {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) return null;
        int step = Math.max(1, Math.max(image.getWidth(), image.getHeight()) / 64);
        Map<Integer, long[]> buckets = new HashMap<>();
        long allR = 0;
        long allG = 0;
        long allB = 0;
        long allCount = 0;
        for (int y = 0; y < image.getHeight(); y += step) {
            for (int x = 0; x < image.getWidth(); x += step) {
                int argb = image.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xff;
                if (alpha < 32) continue;
                int r = (argb >> 16) & 0xff;
                int g = (argb >> 8) & 0xff;
                int b = argb & 0xff;
                allR += r;
                allG += g;
                allB += b;
                allCount += 1;
                int key = ((r >> 3) << 10) | ((g >> 3) << 5) | (b >> 3);
                long[] bucket = buckets.computeIfAbsent(key, ignored -> new long[4]);
                bucket[0] += 1;
                bucket[1] += r;
                bucket[2] += g;
                bucket[3] += b;
            }
        }
        if (allCount == 0) return null;
        long[] best = null;
        for (long[] bucket : buckets.values()) {
            if (best == null || bucket[0] > best[0]) best = bucket;
        }
        if (best == null || best[0] == 0) {
            return toHex(allR / allCount, allG / allCount, allB / allCount);
        }
        long r = best[1] / best[0];
        long g = best[2] / best[0];
        long b = best[3] / best[0];
        if (r > 245 && g > 245 && b > 245) {
            return toHex(allR / allCount, allG / allCount, allB / allCount);
        }
        return toHex(r, g, b);
    }

    private static String toHex(long r, long g, long b) {
        return String.format("#%02x%02x%02x",
                Math.max(0, Math.min(255, r)),
                Math.max(0, Math.min(255, g)),
                Math.max(0, Math.min(255, b)));
    }
}
