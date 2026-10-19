package com.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 路由参数覆盖：在请求发往某个渠道前，按 {@code model_route.param_overrides} 改写 JSON body。
 *
 * <p>支持四种操作（可同时出现）：
 * <ul>
 *   <li>{@code set}：强制覆盖（不管调用方是否传了）；</li>
 *   <li>{@code setIfAbsent}：仅调用方未传时补默认值；</li>
 *   <li>{@code delete}：删除调用方传入但该渠道不支持的字段；</li>
 *   <li>{@code rename}：键名改写，如 {@code {"pictures":"pics"}}。</li>
 * </ul>
 *
 * <p>兼容旧字段：如果 JSON 里没有这些操作键，则整个对象按 {@code set} 处理。
 *
 * <p>路径支持点号（{@code style.type} / {@code generation_config.seed}）；数组下标不在支持范围。
 * 任何解析失败都返回原始 body 并告警——覆盖配置坏了不能把正常请求打死。
 */
public final class ParamOverrideSupport {

    private static final Logger log = LoggerFactory.getLogger(ParamOverrideSupport.class);
    private static final ObjectMapper M = new ObjectMapper();

    private ParamOverrideSupport() {}

    public record Overrides(Map<String, JsonNode> set,
                            Map<String, JsonNode> setIfAbsent,
                            List<String> delete,
                            Map<String, String> rename) {
        static Overrides empty() {
            return new Overrides(Map.of(), Map.of(), List.of(), Map.of());
        }
    }

    /** 解析配置；空/null 返回空覆盖，非法结构抛 IllegalArgumentException（管理端保存时用）。 */
    public static Overrides parse(String json) {
        if (json == null || json.isBlank()) return Overrides.empty();
        JsonNode root;
        try {
            root = M.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("参数覆盖不是合法 JSON：" + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("参数覆盖必须是 JSON 对象");
        }
        boolean structured = root.has("set") || root.has("setIfAbsent")
                || root.has("delete") || root.has("rename");
        Map<String, JsonNode> set = new LinkedHashMap<>();
        Map<String, JsonNode> setIfAbsent = new LinkedHashMap<>();
        List<String> delete = new ArrayList<>();
        Map<String, String> rename = new LinkedHashMap<>();

        if (!structured) {
            root.properties().forEach(e -> set.put(e.getKey(), e.getValue()));
            return new Overrides(set, Map.of(), List.of(), Map.of());
        }

        readObject(root.get("set"), "set", set);
        readObject(root.get("setIfAbsent"), "setIfAbsent", setIfAbsent);
        JsonNode renameNode = root.get("rename");
        if (renameNode != null && !renameNode.isNull()) {
            if (!renameNode.isObject()) throw new IllegalArgumentException("rename 必须是对象，例如 {\"pictures\":\"pics\"}");
            for (Map.Entry<String, JsonNode> entry : renameNode.properties()) {
                if (entry.getKey().isBlank() || !entry.getValue().isTextual()
                        || entry.getValue().asText().isBlank()) {
                    throw new IllegalArgumentException("rename 的键和值都必须是非空字符串");
                }
                rename.put(entry.getKey(), entry.getValue().asText());
            }
        }
        JsonNode del = root.get("delete");
        if (del != null && !del.isNull()) {
            if (!del.isArray()) throw new IllegalArgumentException("delete 必须是字符串数组");
            for (JsonNode item : del) {
                if (!item.isTextual() || item.asText().isBlank()) {
                    throw new IllegalArgumentException("delete 必须是非空字符串数组");
                }
                delete.add(item.asText());
            }
        }
        return new Overrides(set, setIfAbsent, delete, rename);
    }

    public record Result(String body, List<String> steps) {}

    /** 应用到 JSON body；upstreamModel 非空时最后写回 model（上游映射优先级最高）。 */
    public static String apply(String bodyJson, String overridesJson, String upstreamModel) {
        return applyWithTrace(bodyJson, overridesJson, upstreamModel, true).body();
    }

    /**
     * @param allowRename 是否允许 {@code rename}。仅对「原样透传」的 openai_compatible 为 true；
     *                    Gemini / Ollama / 视频适配器会自己解析 canonical 字段，改名会让它们读不到。
     */
    public static String apply(String bodyJson, String overridesJson, String upstreamModel, boolean allowRename) {
        return applyWithTrace(bodyJson, overridesJson, upstreamModel, allowRename).body();
    }

    /** 与 apply 相同，额外返回「实际改了什么」用于请求预览/排障。 */
    public static Result applyWithTrace(String bodyJson, String overridesJson,
                                        String upstreamModel, boolean allowRename) {
        if (bodyJson == null || bodyJson.isBlank()) return new Result(bodyJson, List.of());
        try {
            JsonNode node = M.readTree(bodyJson);
            if (!(node instanceof ObjectNode body)) return new Result(bodyJson, List.of());
            Overrides overrides = parse(overridesJson);
            List<String> steps = new ArrayList<>();
            apply(body, overrides, allowRename, steps);
            if (upstreamModel != null && !upstreamModel.isBlank()) {
                body.put("model", upstreamModel);
                steps.add("上游模型名 model=" + upstreamModel);
            }
            return new Result(M.writeValueAsString(body), steps);
        } catch (Exception e) {
            log.warn("参数覆盖应用失败，按原始请求透传: {}", e.getMessage());
            return new Result(bodyJson, List.of());
        }
    }

    /** 在已解析 body 上执行覆盖（调用方负责序列化）。 */
    public static void apply(ObjectNode body, Overrides overrides) {
        apply(body, overrides, true);
    }

    public static void apply(ObjectNode body, Overrides overrides, boolean allowRename) {
        apply(body, overrides, allowRename, new ArrayList<>());
    }

    private static void apply(ObjectNode body, Overrides overrides, boolean allowRename, List<String> steps) {
        if (overrides == null) return;
        if (allowRename) {
            for (Map.Entry<String, String> e : overrides.rename().entrySet()) {
                if (!hasPath(body, e.getKey())) continue;
                movePath(body, e.getKey(), e.getValue());
                steps.add("rename " + e.getKey() + " → " + e.getValue());
            }
        }
        for (String path : overrides.delete()) {
            if (!hasPath(body, path)) continue;
            removePath(body, path);
            steps.add("delete " + path);
        }
        for (Map.Entry<String, JsonNode> e : overrides.setIfAbsent().entrySet()) {
            if (!hasPath(body, e.getKey())) {
                putPath(body, e.getKey(), e.getValue());
                steps.add("setIfAbsent " + e.getKey() + "=" + e.getValue());
            }
        }
        for (Map.Entry<String, JsonNode> e : overrides.set().entrySet()) {
            putPath(body, e.getKey(), e.getValue());
            steps.add("set " + e.getKey() + "=" + e.getValue());
        }
    }

    private static void readObject(JsonNode node, String name, Map<String, JsonNode> target) {
        if (node == null || node.isNull()) return;
        if (!node.isObject()) throw new IllegalArgumentException(name + " 必须是 JSON 对象");
        node.properties().forEach(e -> target.put(e.getKey(), e.getValue()));
    }

    private static boolean hasPath(ObjectNode root, String path) {
        JsonNode cur = root;
        for (String seg : path.split("\\.")) {
            if (cur == null || !cur.isObject()) return false;
            cur = cur.get(seg);
            if (cur == null || cur.isNull()) return false;
        }
        return true;
    }

    private static void putPath(ObjectNode root, String path, JsonNode value) {
        String[] segs = path.split("\\.");
        ObjectNode cur = root;
        for (int i = 0; i < segs.length - 1; i++) {
            JsonNode next = cur.get(segs[i]);
            ObjectNode obj;
            if (next instanceof ObjectNode existing) {
                obj = existing;
            } else {
                obj = M.createObjectNode();
                cur.set(segs[i], obj);
            }
            cur = obj;
        }
        cur.set(segs[segs.length - 1], value);
    }

    private static void movePath(ObjectNode root, String from, String to) {
        JsonNode value = getPath(root, from);
        if (value == null) return;
        removePath(root, from);
        putPath(root, to, value);
    }

    private static JsonNode getPath(ObjectNode root, String path) {
        JsonNode cur = root;
        for (String seg : path.split("\\.")) {
            if (!(cur instanceof ObjectNode obj)) return null;
            cur = obj.get(seg);
            if (cur == null || cur.isNull()) return null;
        }
        return cur;
    }

    private static void removePath(ObjectNode root, String path) {
        String[] segs = path.split("\\.");
        ObjectNode cur = root;
        for (int i = 0; i < segs.length - 1; i++) {
            JsonNode next = cur.get(segs[i]);
            if (!(next instanceof ObjectNode obj)) return;
            cur = obj;
        }
        cur.remove(segs[segs.length - 1]);
    }
}
