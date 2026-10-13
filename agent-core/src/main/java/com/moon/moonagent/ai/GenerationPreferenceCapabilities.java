package com.moon.moonagent.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成偏好能力归一化：把网关模型的 params 能力转成前端「生成偏好」直接渲染的结构
 * （可选比例 + 清晰度标签/取值键/选项）。
 *
 * <p>不同模型对同一能力的叫法不一致（有的叫尺寸、有的叫质量），因此由后端统一映射：
 * 图片优先 sizes（标签「清晰度」，参数键 size），无 sizes 时回落 qualities（标签「质量」，参数键 quality）；
 * 视频用 resolutions（标签「清晰度」，参数键 resolution）。
 *
 * <p>未声明比例的模型返回空 ratioOptions：前端只提供「智能」，其余比例不可选。
 */
public final class GenerationPreferenceCapabilities {

    private static final ObjectMapper JSON = new ObjectMapper();

    private GenerationPreferenceCapabilities() {
    }

    /** 为模型列表补齐 preferences 能力块；解析失败/非图片视频类型时原样透传。 */
    public static List<Map<String, Object>> decorate(List<Map<String, String>> models, String type) {
        List<Map<String, Object>> decorated = new ArrayList<>();
        if (models == null) return decorated;
        for (Map<String, String> model : models) {
            Map<String, Object> item = new LinkedHashMap<>(model);
            Map<String, Object> capability = capability(model == null ? null : model.get("params"), type);
            if (capability != null) item.put("preferences", capability);
            decorated.add(item);
        }
        return decorated;
    }

    /** 单个模型的能力块；无可用能力时返回 null（前端本地兜底推导）。 */
    static Map<String, Object> capability(String paramsJson, String type) {
        JsonNode params = parse(paramsJson);
        if (params == null) return null;
        Map<String, Object> capability = new LinkedHashMap<>();
        capability.put("ratioOptions", strings(params.path("aspectRatios")));
        String ratioDefault = params.path("aspectRatioDefault").asText("");
        if (!ratioDefault.isBlank()) capability.put("ratioDefault", ratioDefault);
        Map<String, Object> clarity = clarity(params, type);
        if (clarity != null) capability.put("clarity", clarity);
        return capability;
    }

    private static Map<String, Object> clarity(JsonNode params, String type) {
        if ("image".equals(type)) {
            Map<String, Object> bySize = enumSpec(params, "sizes", "sizeDefault", "清晰度", "size");
            return bySize != null
                    ? bySize
                    : enumSpec(params, "qualities", "qualityDefault", "质量", "quality");
        }
        if ("video".equals(type)) {
            return enumSpec(params, "resolutions", "resolutionDefault", "清晰度", "resolution");
        }
        return null;
    }

    private static Map<String, Object> enumSpec(JsonNode params, String field, String defaultField,
                                                String label, String param) {
        List<String> options = strings(params.path(field));
        if (options.isEmpty()) return null;
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("label", label);
        spec.put("param", param);
        spec.put("options", options);
        String value = params.path(defaultField).asText("");
        spec.put("default", options.contains(value) ? value : options.getFirst());
        return spec;
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("");
            if (!value.isBlank() && !values.contains(value)) values.add(value);
        }
        return values;
    }

    private static JsonNode parse(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) return null;
        try {
            JsonNode node = JSON.readTree(paramsJson);
            return node != null && node.isObject() ? node : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
