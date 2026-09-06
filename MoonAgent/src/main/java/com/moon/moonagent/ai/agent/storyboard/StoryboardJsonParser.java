package com.moon.moonagent.ai.agent.storyboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分镜/素材 JSON 确定性解析器：兼容 Markdown 代码块、前后解释、
 * {"scenes":[...]}/{"items":[...]} 包装、sceneNumber 字符串、尾逗号与缺失字段。
 * 失败且没有任何合法分镜时才抛错；存在合法分镜时跳过非法单镜，不丢整批。
 */
public final class StoryboardJsonParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StoryboardJsonParser() {}

    /** 解析分镜列表：返回每镜 Map（字段已归一，sceneNumber 兼容字符串）。 */
    public static List<Map<String, Object>> parseScenes(String raw) {
        JsonNode arrayNode = arrayNode(raw, "scenes", "items", "data", "sceneList", "result");
        if (arrayNode == null || !arrayNode.isArray()) {
            throw new IllegalArgumentException("未找到分镜 JSON 数组");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode node : arrayNode) {
            if (!node.isObject()) continue;
            Map<String, Object> scene = sceneToMap(node);
            if (scene != null) out.add(scene);
        }
        if (out.isEmpty()) throw new IllegalArgumentException("没有合法分镜");
        return out;
    }

    /** 解析素材绑定：返回 {"bindings":[...],"message":""}。 */
    public static Map<String, Object> parseBindResult(String raw) {
        JsonNode root = rootNode(raw);
        JsonNode bindings = root.path("bindings");
        if (!bindings.isArray()) bindings = root;
        List<Map<String, Object>> list = new ArrayList<>();
        for (JsonNode node : bindings) {
            if (!node.isObject()) continue;
            list.add(bindingToMap(node));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bindings", list);
        out.put("message", root.path("message").asText(""));
        return out;
    }

    /** 解析分镜方案：返回 {"scenes":[...],"aspectRatio":"","message":""}。 */
    public static Map<String, Object> parseDesignResult(String raw) {
        JsonNode root = rootNode(raw);
        JsonNode arr = root.path("scenes");
        if (!arr.isArray()) arr = arrayNode(raw, "scenes", "items");
        List<Map<String, Object>> list = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode node : arr) {
                if (!node.isObject()) continue;
                Map<String, Object> scene = sceneToMap(node);
                if (scene != null) list.add(scene);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenes", list);
        out.put("aspectRatio", root.path("aspectRatio").asText("16:9"));
        out.put("message", root.path("message").asText(""));
        return out;
    }

    /** JSON 数组节点定位：优先对象里的字段，否则根数组。 */
    private static JsonNode arrayNode(String raw, String... keys) {
        JsonNode root = rootNode(raw);
        if (root.isArray()) return root;
        if (!root.isObject()) return null;
        for (String key : keys) {
            JsonNode node = root.path(key);
            if (node.isArray()) return node;
        }
        return null;
    }

    private static JsonNode rootNode(String raw) {
        String clean = normalizeJson(extractJson(raw));
        try {
            return MAPPER.readTree(clean);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 JSON: " + e.getMessage(), e);
        }
    }

    /** 提取最外层 JSON：剥离代码块，扫描首个 [ 或 { 的平衡括号。 */
    static String extractJson(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        int jsonIdx = s.indexOf("```json");
        if (jsonIdx >= 0) {
            s = s.substring(jsonIdx + 7);
            int end = s.lastIndexOf("```");
            if (end >= 0) s = s.substring(0, end);
        } else if (s.startsWith("```")) {
            s = s.substring(3);
            int end = s.lastIndexOf("```");
            if (end >= 0) s = s.substring(0, end);
        }
        int start = -1;
        char open = ' ';
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[' || c == '{') { start = i; open = c; break; }
        }
        if (start < 0) return s.trim();
        char close = open == '[' ? ']' : '}';
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return s.substring(start, i + 1);
            }
        }
        return s.substring(start);
    }

    /** 安全归一化：只处理常见、可逆且不会破坏合法 JSON 的形态。 */
    static String normalizeJson(String json) {
        if (json == null || json.isBlank()) return "";
        String s = json.trim();
        // 尾逗号：,]} / ,} / ,]
        s = s.replaceAll(",\\s*([}\\]])", "$1");
        // undefined / None / NaN（非字符串宽泛替换；生产内容罕见，出现时宁可置 null）
        s = s.replaceAll("(?i)\\b(undefined|none|nan)\\b", "null");
        return s;
    }

    private static Map<String, Object> sceneToMap(JsonNode node) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sceneNumber", sceneNumber(node));
        String scriptContent = text(node, "scriptContent");
        String imagePrompt = text(node, "imagePrompt");
        String videoPrompt = text(node, "videoPrompt");
        m.put("scriptContent", scriptContent);
        m.put("imagePrompt", imagePrompt);
        m.put("videoPrompt", videoPrompt);
        m.put("negativePrompt", text(node, "negativePrompt"));
        m.put("cameraMovement", text(node, "cameraMovement"));
        m.put("shotType", text(node, "shotType"));
        m.put("soundDesign", text(node, "soundDesign"));
        JsonNode refs = node.path("referenceBindings");
        List<Map<String, Object>> refList = new ArrayList<>();
        if (refs.isArray()) {
            for (JsonNode r : refs) {
                if (r.isObject()) refList.add(bindingToMap(r));
            }
        }
        m.put("referenceBindings", refList);
        // 没有任何内容的分镜视为非法单镜（跳过，不丢整批）
        if (scriptContent.isBlank() && imagePrompt.isBlank() && videoPrompt.isBlank()) return null;
        return m;
    }

    private static Map<String, Object> bindingToMap(JsonNode node) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("assetId", text(node, "assetId"));
        m.put("url", text(node, "url"));
        m.put("kind", text(node, "kind"));
        m.put("purposeText", text(node, "purposeText"));
        m.put("supportedPrompt", text(node, "supportedPrompt"));
        m.put("groupLabel", text(node, "groupLabel"));
        String role = text(node, "role");
        m.put("role", normalizeRole(role));
        return m;
    }

    private static int sceneNumber(JsonNode node) {
        JsonNode n = node.path("sceneNumber");
        if (n.isInt()) return n.asInt();
        String raw = n.asText("");
        if (raw.isBlank()) return 0;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isBlank()) return 0;
        try { return Integer.parseInt(digits); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asText("");
    }

    private static String normalizeRole(String raw) {
        if (raw == null || raw.isBlank()) return "REFERENCE";
        return switch (raw.trim().toUpperCase()) {
            case "FIRST_FRAME", "LAST_FRAME", "FIRST_LAST_FRAME", "FULL_REFERENCE" -> raw.trim().toUpperCase();
            default -> "REFERENCE";
        };
    }
}
