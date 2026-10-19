package com.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 把模型 Spec 里的参数值映射（{@code request[].map}）应用到上游请求体。
 *
 * <p>用途：前端/Agent 用人类可读值（如 {@code 2K}、{@code 16:9}）渲染表单，
 * 网关在发往渠道前按 Spec 映射成上游真实值（如 {@code 2048x2048}、{@code 1280x720}）。
 * 只处理有 {@code map} 的参数；没有映射的字段原样透传。
 *
 * <p>支持点路径和一层嵌套（object 递归、arrayofobject 逐项递归）。
 * 解析失败时返回原始 body 并告警，不影响正常请求。
 */
public final class SpecRequestMapping {

    private static final Logger log = LoggerFactory.getLogger(SpecRequestMapping.class);
    private static final ObjectMapper M = new ObjectMapper();

    private SpecRequestMapping() {}

    public record Result(String body, List<String> steps) {}

    public static String apply(String bodyJson, String specJson) {
        return applyWithTrace(bodyJson, specJson, true).body();
    }

    /**
     * @param renameKeys 是否执行 key/aliases → upstreamKey 的重命名。
     *                   仅对「请求体原样透传」的协议（openai_compatible）为 true；
     *                   Gemini / Ollama / 视频适配器会自己解析 canonical 字段再构建载荷，重命名反而会让它们读不到。
     */
    public static String apply(String bodyJson, String specJson, boolean renameKeys) {
        return applyWithTrace(bodyJson, specJson, renameKeys).body();
    }

    /** 与 {@link #apply(String, String, boolean)} 相同，额外返回「实际改了什么」用于请求预览/排障。 */
    public static Result applyWithTrace(String bodyJson, String specJson, boolean renameKeys) {
        if (bodyJson == null || bodyJson.isBlank() || specJson == null || specJson.isBlank()) {
            return new Result(bodyJson, List.of());
        }
        try {
            JsonNode spec = M.readTree(specJson);
            JsonNode request = spec.path("request");
            if (!request.isArray() || request.isEmpty()) return new Result(bodyJson, List.of());
            JsonNode node = M.readTree(bodyJson);
            if (!(node instanceof ObjectNode body)) return new Result(bodyJson, List.of());
            List<String> steps = new ArrayList<>();
            applyFields(body, request, renameKeys, steps);
            return new Result(M.writeValueAsString(body), steps);
        } catch (Exception e) {
            log.warn("参数值映射失败，按原始请求透传: {}", e.getMessage());
            return new Result(bodyJson, List.of());
        }
    }

    /** 只做值映射，不做键重命名（适配器协议、视频创建用）。 */
    public static String applyValuesOnly(String bodyJson, String specJson) {
        return apply(bodyJson, specJson, false);
    }

    private static void applyFields(ObjectNode body, JsonNode fields, boolean renameKeys, List<String> steps) {
        for (JsonNode field : fields) {
            String key = field.path("key").asText("");
            if (key.isBlank()) continue;
            String upstreamKey = field.path("upstreamKey").asText("");
            if (upstreamKey.isBlank()) upstreamKey = key;

            // 可选：网关补齐默认值。只对显式 injectDefault=true 的参数生效，避免把 n 之类厂商不认的字段硬塞给上游。
            JsonNode defaultNode = field.get("default");
            if (field.path("injectDefault").asBoolean(false)
                    && defaultNode != null && !defaultNode.isNull()
                    && !hasPath(body, key) && !hasAlias(body, field)) {
                setPath(body, key, defaultNode.deepCopy());
                steps.add("注入默认值 " + key + "=" + defaultNode);
            }

            if (renameKeys) {
                // 通用键/别名 → 上游键：UI 可以继续用 pictures，请求体最终写 pics
                String source = key;
                if (!hasPath(body, key)) {
                    JsonNode aliases = field.get("aliases");
                    if (aliases != null && aliases.isArray()) {
                        for (JsonNode alias : aliases) {
                            String candidate = alias.asText("");
                            if (!candidate.isBlank() && hasPath(body, candidate)) {
                                source = candidate;
                                steps.add("使用别名 " + candidate + " → " + key);
                                break;
                            }
                        }
                    }
                }
                if (!source.equals(upstreamKey) && hasPath(body, source)) {
                    movePath(body, source, upstreamKey);
                    steps.add("键映射 " + source + " → " + upstreamKey);
                }
                // 别名和通用键同时存在时，避免把别名也发给上游
                JsonNode aliases = field.get("aliases");
                if (aliases != null && aliases.isArray()) {
                    for (JsonNode alias : aliases) {
                        String candidate = alias.asText("");
                        if (!candidate.isBlank() && !candidate.equals(upstreamKey) && hasPath(body, candidate)) {
                            removePath(body, candidate);
                            steps.add("移除别名 " + candidate);
                        }
                    }
                }
            } else {
                upstreamKey = key;
            }

            JsonNode map = field.get("map");
            if (map != null && map.isObject() && !map.isEmpty()) {
                JsonNode before = resolve(body, upstreamKey);
                applyMap(body, upstreamKey, map);
                JsonNode after = resolve(body, upstreamKey);
                if (before != null && after != null && !before.equals(after)) {
                    steps.add("值映射 " + upstreamKey + ": " + before.asText() + " → " + after.asText());
                }
            }

            JsonNode subFields = field.get("fields");
            if (subFields != null && subFields.isArray() && !subFields.isEmpty()) {
                JsonNode target = resolve(body, upstreamKey);
                if (target instanceof ObjectNode obj) {
                    applyFields(obj, subFields, renameKeys, steps);
                } else if (target != null && target.isArray()) {
                    for (JsonNode item : target) {
                        if (item instanceof ObjectNode obj) applyFields(obj, subFields, renameKeys, steps);
                    }
                }
            }
        }
    }

    private static boolean hasAlias(ObjectNode body, JsonNode field) {
        JsonNode aliases = field.get("aliases");
        if (aliases == null || !aliases.isArray()) return false;
        for (JsonNode alias : aliases) {
            String candidate = alias.asText("");
            if (!candidate.isBlank() && hasPath(body, candidate)) return true;
        }
        return false;
    }

    private static boolean hasPath(ObjectNode body, String path) {
        return resolve(body, path) != null;
    }

    /** 把 from 路径的值移动到 to 路径（先取值、删旧键、写新键）；任一为空则不动。 */
    private static void movePath(ObjectNode body, String from, String to) {
        JsonNode value = resolve(body, from);
        if (value == null) return;
        removePath(body, from);
        setPath(body, to, value);
    }

    private static void setPath(ObjectNode root, String path, JsonNode value) {
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

    private static void applyMap(ObjectNode body, String key, JsonNode map) {
        String[] segs = key.split("\\.");
        ObjectNode parent = body;
        for (int i = 0; i < segs.length - 1; i++) {
            JsonNode next = parent.get(segs[i]);
            if (!(next instanceof ObjectNode obj)) return;
            parent = obj;
        }
        String leaf = segs[segs.length - 1];
        JsonNode value = parent.get(leaf);
        if (value == null || value.isNull() || value.isContainerNode()) return;
        JsonNode mapped = map.get(value.asText());
        if (mapped == null) return;
        // 映射表常用字符串写值；按原字段类型做一次轻量还原，避免 boolean 变成 "true"、number 变成 "4"
        if (value.isBoolean() && mapped.isTextual()
                && ("true".equalsIgnoreCase(mapped.asText()) || "false".equalsIgnoreCase(mapped.asText()))) {
            parent.put(leaf, Boolean.parseBoolean(mapped.asText()));
        } else if (value.isInt() && mapped.isTextual()) {
            try {
                parent.put(leaf, Integer.parseInt(mapped.asText().trim()));
            } catch (NumberFormatException e) {
                parent.set(leaf, mapped);
            }
        } else if (value.isNumber() && mapped.isTextual()) {
            try {
                parent.put(leaf, Double.parseDouble(mapped.asText().trim()));
            } catch (NumberFormatException e) {
                parent.set(leaf, mapped);
            }
        } else {
            parent.set(leaf, mapped);
        }
    }

    private static JsonNode resolve(ObjectNode body, String key) {
        JsonNode cur = body;
        for (String seg : key.split("\\.")) {
            if (!(cur instanceof ObjectNode obj)) return null;
            cur = obj.get(seg);
            if (cur == null || cur.isNull()) return null;
        }
        return cur;
    }
}
