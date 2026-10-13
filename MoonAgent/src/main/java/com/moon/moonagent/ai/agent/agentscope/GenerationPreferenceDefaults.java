package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.AgentGenerationPreferences;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把用户「生成偏好」作为缺省值填入媒体生成工具入参：只补工具真正支持的键，
 * 已有值一律不覆盖（模型自己填的、或用户在计划卡里改过的优先）。
 *
 * <p>自动模式（无偏好）或工具不需要偏好时原样返回。
 */
@Component
public class GenerationPreferenceDefaults {

    /** 工具 → 该工具支持的偏好键（顺序 = 填充顺序）。 */
    private static final Map<String, List<String>> TOOL_KEYS = Map.of(
            "generate_image", List.of("model", "size", "quality", "aspect_ratio"),
            "generate_image_batch", List.of("size", "quality",
                    "video_aspect_ratio", "video_resolution"),
            "generate_video", List.of("model", "resolution", "aspect_ratio"),
            "generate_storyboard_image", List.of("size", "quality"),
            "generate_storyboard_video", List.of("model", "resolution", "aspect_ratio"),
            "generate_storyboard_videos", List.of("resolution", "aspect_ratio"));

    /** 工具参数键 → 偏好载荷键。 */
    private static final Map<String, String> SOURCE_KEYS = Map.of(
            "model", "model",
            "size", "size",
            "quality", "quality",
            "aspect_ratio", "aspectRatio",
            "resolution", "resolution",
            "video_aspect_ratio", "aspectRatio",
            "video_resolution", "resolution");

    /**
     * 补齐入参缺省值。工具无关或无需补齐时返回原 Map（避免无谓拷贝）。
     */
    public Map<String, Object> applyDefaults(String toolName, Map<String, Object> input,
                                             AgentGenerationPreferences preferences) {
        if (toolName == null || preferences == null || !preferences.hasMediaOverrides()) {
            return input;
        }
        List<String> keys = TOOL_KEYS.get(toolName);
        if (keys == null) return input;
        Map<String, String> preference = preferences.preferenceFor(toolName);
        if (preference.isEmpty()) return input;

        Map<String, Object> merged = null;
        for (String key : keys) {
            String source = SOURCE_KEYS.get(key);
            if (source == null) continue;
            // 批量生图的后续视频参数（video_*）读视频偏好，其余键读该工具所属分组
            Map<String, String> group = key.startsWith("video_") ? preferences.video() : preference;
            String value = group.get(source);
            if (value == null || value.isBlank()) continue;
            Object existing = input == null ? null : input.get(key);
            if (existing != null && !String.valueOf(existing).isBlank()) continue;
            if (merged == null) merged = new LinkedHashMap<>(input == null ? Map.of() : input);
            merged.put(key, value);
        }
        return merged == null ? input : merged;
    }
}
