package com.moon.xiaoyuecanvas.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Server-side mirror of the CanvasGraphV2 node registry and port contracts. */
public final class CanvasNodeRegistry {

    public record Port(
            String id,
            String label,
            String media,
            Set<String> acceptsKinds,
            int min,
            int max,
            boolean required,
            boolean ordered
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", id);
            value.put("label", label);
            value.put("media", media);
            value.put("acceptsKinds", List.copyOf(acceptsKinds));
            value.put("min", min);
            value.put("max", max);
            value.put("required", required);
            value.put("ordered", ordered);
            return value;
        }
    }

    public record NodeType(
            String kind,
            String title,
            String category,
            int width,
            int height,
            String accent,
            List<Port> inputs,
            List<Port> outputs
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("kind", kind);
            value.put("title", title);
            value.put("category", category);
            value.put("defaultSize", Map.of("width", width, "height", height));
            value.put("accent", accent);
            value.put("inputs", inputs.stream().map(Port::toMap).toList());
            value.put("outputs", outputs.stream().map(Port::toMap).toList());
            return value;
        }
    }

    private static final Set<String> IMAGE_PRODUCERS = Set.of(
            "imageGeneration", "textToImage", "imageToImage", "batchImageRefine", "shot");
    private static final Set<String> VIDEO_PRODUCERS = Set.of(
            "videoGeneration", "textToVideo", "imageToVideo", "firstLastFrameToVideo", "referenceToVideo",
            "videoMerge", "shot");

    /**
     * 已被前端合并、不再作为可创建节点暴露的旧生成 kind。
     *
     * <p>它们仍注册在 {@link #TYPES} 里：存量画布里的旧节点必须能通过校验（否则连线会报
     * NODE_TYPE_UNKNOWN），但不出现在 {@link #listMaps()} 的节点目录中，模型只能创建合并后的
     * {@code imageGeneration} / {@code videoGeneration}，端口与前端渲染契约保持一致。
     */
    private static final Set<String> MERGED_LEGACY_KINDS = Set.of(
            "textToImage", "imageToImage", "batchImageRefine",
            "textToVideo", "imageToVideo", "firstLastFrameToVideo", "referenceToVideo");

    private static final Map<String, NodeType> TYPES = new LinkedHashMap<>();

    static {
        register(new NodeType("text", "文字", "输入", 380, 280, "#f4c95d",
                List.of(), List.of(output("text", "文字", "text"))));
        register(new NodeType("script", "剧本", "输入", 420, 320, "#a9c9f5",
                List.of(), List.of(output("script", "剧本", "script"))));
        register(new NodeType("note", "批注", "辅助", 250, 250, "#d7ff54",
                List.of(), List.of()));
        // 分镜：前端节点库里的 storyboardPlanner → shot 结构化占位，缺了它们 agent 建链路会报未知节点类型
        register(new NodeType("storyboardPlanner", "分镜拆解", "分镜", 520, 420, "#9d9d9d",
                List.of(
                        input("script", "文字", "text", Set.of("text", "script"), 1, 1, true),
                        input("reference", "参考图", "image", Set.of("image"), 0, 9, false, true)),
                List.of(new Port("shots", "镜头", "shot", Set.of("shot"), 0, -1, false, true))));
        register(new NodeType("shot", "镜头", "分镜", 360, 300, "#c2c2c2",
                List.of(
                        input("origin", "分镜来源", "shot", Set.of("storyboardPlanner"), 0, 1, false),
                        input("reference", "参考", "image", Set.of("image"), 0, 9, false, true)),
                List.of(output("image", "图片结果", "image"), output("video", "视频结果", "video"))));
        register(new NodeType("image", "图片", "输入", 250, 250, "#ff775f",
                List.of(input("origin", "生成来源", "image", IMAGE_PRODUCERS, 0, 1, false)),
                List.of(output("image", "图片结果", "image"))));
        register(new NodeType("video", "视频", "输入", 420, 236, "#8f9cff",
                List.of(input("origin", "生成来源", "video", VIDEO_PRODUCERS, 0, 1, false)),
                List.of(output("video", "视频结果", "video"))));
        register(new NodeType("textToImage", "文生图", "图像生成", 520, 420, "#b48cff",
                List.of(input("prompt", "提示词", "text", Set.of("text", "script"), 0, 1, false)),
                List.of(output("image", "图片结果", "image"))));
        register(new NodeType("imageToImage", "图生图", "图像生成", 520, 420, "#b48cff",
                List.of(
                        input("source", "源图", "image", Set.of("image"), 1, 1, true),
                        input("reference", "参考图", "image", Set.of("image"), 0, 5, false, true)),
                List.of(output("image", "图片结果", "image"))));
        register(new NodeType("batchImageRefine", "批量完善图片", "图像生成", 560, 460, "#b48cff",
                List.of(input("images", "源图片", "image", Set.of("image"), 1, 12, true, true)),
                List.of(output("image", "图片结果", "image"))));
        register(new NodeType("textToVideo", "文生视频", "视频生成", 520, 420, "#8f9cff",
                List.of(input("prompt", "提示词", "text", Set.of("text", "script"), 0, 1, false)),
                List.of(output("video", "视频结果", "video"))));
        register(new NodeType("imageToVideo", "图生视频", "视频生成", 520, 420, "#8f9cff",
                List.of(input("first", "首帧", "image", Set.of("image"), 1, 1, true)),
                List.of(output("video", "视频结果", "video"))));
        register(new NodeType("firstLastFrameToVideo", "首尾帧生视频", "视频生成", 560, 420, "#8f9cff",
                List.of(
                        input("first", "首帧", "image", Set.of("image"), 1, 1, true),
                        input("last", "尾帧", "image", Set.of("image"), 1, 1, true)),
                List.of(output("video", "视频结果", "video"))));
        register(new NodeType("referenceToVideo", "参考物生视频", "视频生成", 560, 460, "#8f9cff",
                List.of(
                        input("reference", "参考图", "image", Set.of("image"), 1, 6, true, true),
                        input("video", "参考视频", "video", Set.of("video"), 0, 1, false)),
                List.of(output("video", "视频结果", "video"))));
        register(new NodeType("videoMerge", "视频合并", "视频处理", 560, 440, "#8f9cff",
                List.of(input("clips", "视频片段", "video",
                        Set.of("video", "videoGeneration", "textToVideo", "imageToVideo",
                                "firstLastFrameToVideo", "referenceToVideo", "shot", "videoMerge"),
                        2, 12, true, true)),
                List.of(output("video", "视频结果", "video"))));
        // ── 合并后的两个生成节点（前端节点库只展示这两个；旧 kind 仍可解析但不再出现在目录里）──
        register(new NodeType("imageGeneration", "图片生成", "图像生成", 480, 560, "#9d9d9d",
                List.of(
                        input("prompt", "提示词", "text", Set.of("text", "script"), 0, 1, false),
                        input("reference", "参考图", "image", Set.of("image"), 0, 14, false, true)),
                List.of(output("image", "图片结果", "image"))));
        register(new NodeType("videoGeneration", "视频生成", "视频生成", 480, 520, "#a0a0a0",
                List.of(
                        input("prompt", "提示词", "text", Set.of("text", "script"), 0, 1, false),
                        input("reference", "参考图", "image", Set.of("image"), 0, 9, false, true),
                        input("video", "参考视频", "video", Set.of("video"), 0, 1, false)),
                List.of(output("video", "视频结果", "video"))));
    }

    private CanvasNodeRegistry() {
    }

    public static NodeType get(String kind) {
        return TYPES.get(normalizeAlias(kind));
    }

    public static NodeType require(String kind) {
        NodeType type = get(kind);
        if (type == null) {
            throw new CanvasGraphCommandException("NODE_TYPE_UNKNOWN", "未知画布节点类型: " + kind);
        }
        return type;
    }

    /** 暴露给模型的节点目录：只含可创建的 canonical kind（旧合并 kind 仅保留校验能力）。 */
    public static List<NodeType> list() {
        return TYPES.values().stream()
                .filter(type -> !MERGED_LEGACY_KINDS.contains(type.kind()))
                .toList();
    }

    public static List<Map<String, Object>> listMaps() {
        return list().stream().map(NodeType::toMap).toList();
    }

    public static String normalizeAlias(String kind) {
        if (kind == null || kind.isBlank()) return "";
        return switch (kind.trim()) {
            case "prompt" -> "text";
            case "imageGen" -> "imageGeneration";
            case "videoGen" -> "videoGeneration";
            default -> kind.trim();
        };
    }

    private static void register(NodeType type) {
        TYPES.put(type.kind(), type);
    }

    private static Port input(String id, String label, String media, Set<String> accepts, int min, int max, boolean required) {
        return input(id, label, media, accepts, min, max, required, false);
    }

    private static Port input(String id, String label, String media, Set<String> accepts, int min, int max,
                              boolean required, boolean ordered) {
        return new Port(id, label, media, accepts, min, max, required, ordered);
    }

    private static Port output(String id, String label, String media) {
        return new Port(id, label, media, Set.of(), 0, 1, false, false);
    }
}
