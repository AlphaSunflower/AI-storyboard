package com.moon.moonagent.ai.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-turn media generation preferences from the chat composer（生成偏好）。
 *
 * <p>自动（auto）= 智能体自行决定比例 / 清晰度 / 模型，此时不带任何参数；
 * 自定义时 image / video 各自携带已解析成网关参数名的取值
 * （model / aspectRatio / size 或 quality / resolution）。
 *
 * <p>{@code ignoreGenerationPlan} 只作用于媒体生成类工具：这些工具不再走方案确认卡。
 */
public record AgentGenerationPreferences(
        boolean auto,
        boolean ignoreGenerationPlan,
        Map<String, String> image,
        Map<String, String> video) {

    /** 未携带偏好（自动且不忽略方案）时的缺省值：行为与改造前完全一致。 */
    public static final AgentGenerationPreferences NONE =
            new AgentGenerationPreferences(true, false, Map.of(), Map.of());

    /** 需要跳过方案确认的媒体生成工具（生图、生视频、生镜）。 */
    public static final Set<String> MEDIA_GENERATION_TOOLS = Set.of(
            "generate_image",
            "generate_image_batch",
            "generate_video",
            "generate_storyboard_image",
            "generate_storyboard_images",
            "generate_storyboard_video",
            "generate_storyboard_videos");

    private static final Set<String> IMAGE_TOOLS = Set.of(
            "generate_image", "generate_image_batch",
            "generate_storyboard_image", "generate_storyboard_images");

    /** 提示词里的稳定顺序：模型 → 比例 → 清晰度，避免 Map 顺序造成同一偏好文案抖动。 */
    private static final List<String> DESCRIBE_ORDER =
            List.of("model", "aspectRatio", "size", "quality", "resolution");

    public AgentGenerationPreferences {
        image = image == null ? Map.of() : Map.copyOf(clean(image));
        video = video == null ? Map.of() : Map.copyOf(clean(video));
    }

    /** 未携带任何偏好（自动且不忽略方案）。 */
    public static AgentGenerationPreferences none() {
        return NONE;
    }

    public static AgentGenerationPreferences of(boolean auto, boolean ignoreGenerationPlan,
                                               Map<String, String> image,
                                               Map<String, String> video) {
        return new AgentGenerationPreferences(auto, ignoreGenerationPlan, image, video);
    }

    /** 该工具是否属于媒体生成类（生图 / 生视频 / 生镜）。 */
    public static boolean isMediaGenerationTool(String toolName) {
        return toolName != null && MEDIA_GENERATION_TOOLS.contains(toolName);
    }

    /** 工具对应的偏好分组：图片类工具用 image，其余媒体生成工具用 video，非媒体工具为空。 */
    public Map<String, String> preferenceFor(String toolName) {
        if (!isMediaGenerationTool(toolName)) return Map.of();
        return IMAGE_TOOLS.contains(toolName) ? image : video;
    }

    /** 自动模式下不注入参数提示（交给智能体决定）。 */
    public boolean hasMediaOverrides() {
        return !auto && (!image.isEmpty() || !video.isEmpty());
    }

    /** 是否需要在系统提示里声明偏好（自定义参数或忽略方案）。 */
    public boolean hasPromptHints() {
        return hasMediaOverrides() || ignoreGenerationPlan;
    }

    /** 提示词用的可读摘要：仅列出非空项。 */
    public String describe(Map<String, String> preference) {
        if (preference == null || preference.isEmpty()) return "";
        List<String> parts = new java.util.ArrayList<>();
        List<String> keys = new java.util.ArrayList<>(DESCRIBE_ORDER);
        preference.keySet().stream()
                .filter(key -> !DESCRIBE_ORDER.contains(key))
                .sorted()
                .forEach(keys::add);
        for (String key : keys) {
            String value = preference.get(key);
            if (value == null || value.isBlank()) continue;
            String label = switch (key) {
                case "model" -> "model";
                case "aspectRatio" -> "aspect ratio";
                case "size" -> "size";
                case "quality" -> "quality";
                case "resolution" -> "resolution";
                default -> key;
            };
            parts.add(label + "=" + value);
        }
        return String.join(", ", parts);
    }

    private static Map<String, String> clean(Map<String, String> source) {
        Map<String, String> cleaned = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null || key.isBlank()) return;
            if (value == null || value.isBlank()) return;
            cleaned.put(key.trim(), value.trim());
        });
        return cleaned;
    }
}
