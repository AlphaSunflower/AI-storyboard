package com.moon.moonagent.ai.agent.human;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MoonAgent 本地工具请求人工选择的标准化载荷。
 *
 * <p>用户只看到 title；真实 ID、对象或复合参数放在 value 中，提交后再回传给模型。
 */
public record HumanInputRequest(
        String prompt,
        List<Map<String, Object>> options,
        boolean allowCustom
) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static HumanInputRequest fromToolCall(
            String prompt,
            List<Map<String, Object>> rawOptions,
            Boolean allowCustom
    ) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        if (rawOptions != null) {
            for (int i = 0; i < rawOptions.size(); i++) {
                Map<String, Object> raw = rawOptions.get(i);
                if (raw == null) continue;
                String title = stringValue(raw.get("title"));
                String sourceId = stringValue(raw.get("id"));
                Object value = raw.containsKey("value") ? raw.get("value") : sourceId;
                if (title.isBlank()) title = value == null ? "选项 " + (i + 1) : String.valueOf(value);
                Map<String, Object> option = new LinkedHashMap<>();
                option.put("id", "human-option-" + (normalized.size() + 1));
                option.put("title", title);
                option.put("value", value);
                normalized.add(option);
            }
        }
        return new HumanInputRequest(
                prompt == null || prompt.isBlank() ? "请选择后继续：" : prompt.trim(),
                List.copyOf(normalized),
                Boolean.TRUE.equals(allowCustom));
    }

    public Map<String, Object> toToolResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("moonHumanInput", true);
        result.put("prompt", prompt);
        result.put("options", options);
        result.put("allowCustom", allowCustom);
        return result;
    }

    public static Optional<HumanInputRequest> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return Optional.empty();
        try {
            JsonNode root = OBJECT_MAPPER.readTree(raw.substring(start, end + 1));
            if (!root.path("moonHumanInput").asBoolean(false)) return Optional.empty();
            List<Map<String, Object>> options = OBJECT_MAPPER.convertValue(
                    root.path("options"),
                    new TypeReference<List<Map<String, Object>>>() {});
            return Optional.of(new HumanInputRequest(
                    root.path("prompt").asText("请选择后继续："),
                    options == null ? List.of() : List.copyOf(options),
                    root.path("allowCustom").asBoolean(false)));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
