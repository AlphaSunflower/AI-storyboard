package com.moon.moonagent.ai.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.net.URI;

/**
 * 前端画布窗口持有的工作区上下文。只要该模式存在，意图识别就不得流向非画布链路。
 */
public record CanvasSessionContext(
        String mode,
        String projectId,
        String canvasId,
        List<CanvasMediaRef> mediaRefs,
        List<String> excludedMediaUrls
) {
    public static final String EDIT_MODE = "canvas-edit";
    private static final ObjectMapper CODEC = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public CanvasSessionContext(String mode, String projectId, String canvasId, List<CanvasMediaRef> mediaRefs) {
        this(mode, projectId, canvasId, mediaRefs, List.of());
    }

    @JsonIgnore
    public boolean isCanvasEditMode() {
        return EDIT_MODE.equals(mode)
                && projectId != null && !projectId.isBlank()
                && canvasId != null && !canvasId.isBlank();
    }

    public List<CanvasMediaRef> normalizedMediaRefs() {
        return mediaRefs == null ? List.of() : mediaRefs.stream().filter(ref -> ref != null).toList();
    }

    public List<String> normalizedExcludedMediaUrls() {
        return excludedMediaUrls == null ? List.of() : excludedMediaUrls.stream()
                .filter(url -> url != null && !url.isBlank())
                .toList();
    }

    public boolean isMediaExcluded(String url) {
        String path = mediaPath(url);
        return !path.isBlank() && normalizedExcludedMediaUrls().stream()
                .map(CanvasSessionContext::mediaPath)
                .anyMatch(path::equals);
    }

    private static String mediaPath(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            String path = raw.startsWith("http://") || raw.startsWith("https://")
                    ? URI.create(raw).getPath()
                    : raw;
            int query = path.indexOf('?');
            if (query >= 0) path = path.substring(0, query);
            int fragment = path.indexOf('#');
            if (fragment >= 0) path = path.substring(0, fragment);
            return path;
        } catch (Exception ignored) {
            return raw;
        }
    }

    public String toJson() {
        try {
            return CODEC.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("序列化画布上下文失败", e);
        }
    }

    public static CanvasSessionContext fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return CODEC.readValue(json, CanvasSessionContext.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("解析画布上下文失败", e);
        }
    }
}
