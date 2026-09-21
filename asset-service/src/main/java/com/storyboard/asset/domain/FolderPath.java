package com.storyboard.asset.domain;

import com.storyboard.common.BusinessException;

import java.util.ArrayList;
import java.util.List;

/** Normalizes browser folder-import paths before they are persisted as folder nodes. */
public final class FolderPath {

    private static final int MAX_DEPTH = 32;

    private FolderPath() {
    }

    public static List<String> folderSegments(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return List.of();
        String[] raw = relativePath.replace('\\', '/').split("/");
        List<String> segments = new ArrayList<>();
        for (int index = 0; index < raw.length - 1; index += 1) {
            String segment = raw[index].trim();
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) continue;
            if (segment.length() > 255) throw new BusinessException(40001, "目录名称不能超过 255 个字符");
            segments.add(segment);
        }
        if (segments.size() > MAX_DEPTH) throw new BusinessException(40001, "目录层级不能超过 32 层");
        return List.copyOf(segments);
    }
}
