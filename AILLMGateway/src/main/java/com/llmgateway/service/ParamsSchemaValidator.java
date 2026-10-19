package com.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 模型参数自描述 Schema 的保存前校验器（纯静态，无 Spring 依赖）。
 *
 * <p>校验对象是 {@code model_params.params_schema} 的 mini-spec：
 * {@code {modes, promptKey, request[], response.sync/async}}。它同时被 Admin 编辑器、
 * Agent/画布参数渲染和网关响应归一化消费，所以结构错误必须在保存时挡住，
 * 而不是等 {@link ModelParamsSpecNormalizer#spec(String)} 静默返回 null 后才发现。
 *
 * <p>错误（errors）会阻止保存；警告（warnings）只提示，不阻止（例如未配置 request、
 * response.async 缺 statusPath 时归一化器有兜底）。未知顶层字段只记警告，便于向前兼容。
 */
public final class ParamsSchemaValidator {

    private ParamsSchemaValidator() {}

    private static final ObjectMapper M = new ObjectMapper();

    private static final String TYPE_LIST = "enum/range/number/bool/string/object/array/arrayofobject";
    private static final Set<String> TYPES = Set.of(
            "enum", "range", "number", "bool", "string", "object", "array", "arrayofobject");
    private static final String STATUS_LIST = "queued/running/processing/succeeded/failed";
    private static final Set<String> ROLES = Set.of("image_upload", "reference", "response");
    private static final Set<String> CARDINALITIES = Set.of("single", "multi");
    private static final Set<String> STATUSES = Set.of(
            "queued", "running", "processing", "succeeded", "failed");
    private static final Set<String> MEDIA_MODES = Set.of("media_url", "content_endpoint");
    private static final Set<String> TOP_LEVEL_KEYS = Set.of(
            "schemaVersion", "protocol", "modes", "promptKey", "request", "response", "description", "note");

    /** 单条校验问题：path 用 JSON 风格定位（request[0].options / response.async.stateMap.SUCCESS）。 */
    public record Issue(String path, String message) {}

    /**
     * 校验结果。
     *
     * @param errors     阻断保存的问题
     * @param warnings   可保存但值得提示的问题
     * @param normalized 解析后的 JSON（解析失败为 null）
     */
    public record Result(List<Issue> errors, List<Issue> warnings, JsonNode normalized) {

        public boolean valid() {
            return errors.isEmpty();
        }

        /** 给 BusinessException 用的一行摘要（最多 3 条，避免刷屏）。 */
        public String summary() {
            if (errors.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(3, errors.size()); i++) {
                Issue issue = errors.get(i);
                if (i > 0) sb.append("；");
                sb.append(issue.path()).append(' ').append(issue.message());
            }
            if (errors.size() > 3) sb.append("；等 ").append(errors.size()).append(" 项");
            return sb.toString();
        }
    }

    /** 空/空白 schema 视为「未配置」，直接通过（返回 normalized=null）。 */
    public static Result validate(String json) {
        List<Issue> errors = new ArrayList<>();
        List<Issue> warnings = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return new Result(List.copyOf(errors), List.copyOf(warnings), null);
        }

        JsonNode root;
        try {
            root = M.readTree(json);
        } catch (Exception e) {
            errors.add(new Issue("$", "不是合法 JSON：" + rootMessage(e)));
            return new Result(List.copyOf(errors), List.copyOf(warnings), null);
        }
        if (root == null || !root.isObject()) {
            errors.add(new Issue("$", "顶层必须是 JSON 对象"));
            return new Result(List.copyOf(errors), List.copyOf(warnings), null);
        }

        for (String key : fieldNames(root)) {
            if (!TOP_LEVEL_KEYS.contains(key)) {
                warnings.add(new Issue(key, "未知顶层字段，已忽略"));
            }
        }
        checkSchemaVersion(root, errors);
        checkProtocol(root, errors, warnings);
        checkModes(root, errors, warnings);
        JsonNode promptKey = root.get("promptKey");
        if (promptKey != null && !promptKey.isNull() && !promptKey.isTextual()) {
            errors.add(new Issue("promptKey", "必须是字符串"));
        }

        JsonNode request = root.get("request");
        if (request == null || request.isNull()) {
            warnings.add(new Issue("request", "未配置参数定义，Agent/画布会回退到固定列"));
        } else if (!request.isArray()) {
            errors.add(new Issue("request", "必须是数组"));
        } else {
            validateFields(request, "request", errors, warnings);
        }

        JsonNode response = root.get("response");
        if (response != null && !response.isNull()) {
            validateResponse(response, errors, warnings);
        }

        return new Result(List.copyOf(errors), List.copyOf(warnings), root);
    }

    private static void checkSchemaVersion(JsonNode root, List<Issue> errors) {
        JsonNode version = root.get("schemaVersion");
        if (version == null || version.isNull()) return;
        if (!version.isNumber() || version.asInt() < 1) {
            errors.add(new Issue("schemaVersion", "必须是大于 0 的整数"));
        }
    }

    private static final Set<String> PROTOCOLS = Set.of(
            "openai_json", "openai_compatible", "multipart", "gemini", "minimax", "ollama");

    private static void checkProtocol(JsonNode root, List<Issue> errors, List<Issue> warnings) {
        JsonNode protocol = root.get("protocol");
        if (protocol == null || protocol.isNull()) return;
        if (!protocol.isTextual() || protocol.asText().isBlank()) {
            errors.add(new Issue("protocol", "必须是非空字符串"));
        } else if (!PROTOCOLS.contains(protocol.asText())) {
            warnings.add(new Issue("protocol", "未知协议 " + protocol.asText()
                    + "，将按渠道类型选择适配器"));
        }
    }

    private static void checkModes(JsonNode root, List<Issue> errors, List<Issue> warnings) {
        JsonNode modes = root.get("modes");
        if (modes == null || modes.isNull()) return;
        if (!modes.isArray()) {
            errors.add(new Issue("modes", "必须是字符串数组"));
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < modes.size(); i++) {
            JsonNode mode = modes.get(i);
            String path = "modes[" + i + "]";
            if (!mode.isTextual() || mode.asText().isBlank()) {
                errors.add(new Issue(path, "必须是非空字符串"));
            } else if (!seen.add(mode.asText())) {
                warnings.add(new Issue(path, "重复的 mode：" + mode.asText()));
            }
        }
    }

    private static void validateFields(JsonNode fields, String path,
                                       List<Issue> errors, List<Issue> warnings) {
        Set<String> keys = new LinkedHashSet<>();
        for (int i = 0; i < fields.size(); i++) {
            JsonNode item = fields.get(i);
            String itemPath = path + "[" + i + "]";
            if (item == null || !item.isObject()) {
                errors.add(new Issue(itemPath, "必须是对象"));
                continue;
            }

            String key = text(item, "key");
            if (key == null || key.isBlank()) {
                errors.add(new Issue(itemPath + ".key", "不能为空"));
            } else if (!keys.add(key)) {
                errors.add(new Issue(itemPath + ".key", "重复的 key：" + key));
            }

            String type = text(item, "type");
            if (type == null || type.isBlank()) {
                errors.add(new Issue(itemPath + ".type", "不能为空"));
            } else if (!TYPES.contains(type)) {
                errors.add(new Issue(itemPath + ".type",
                        "不支持的类型 " + type + "（可选 " + TYPE_LIST + "）"));
            }

            checkBoolean(item, "show", itemPath, errors);
            checkText(item, "label", itemPath, errors, false);
            checkRole(item, itemPath, errors);
            checkKeyAliases(item, itemPath, errors, warnings);
            checkDefault(item, itemPath, type, errors);
            checkOptions(item, itemPath, type, errors, warnings);
            checkRange(item, itemPath, type, errors);
            checkConditions(item, itemPath, "visibleWhen", errors);
            checkConditions(item, itemPath, "requiredWhen", errors);
            checkMap(item, itemPath, errors, warnings);
            checkMaxItems(item, itemPath, errors);

            boolean nested = "object".equals(type) || "arrayofobject".equals(type);
            JsonNode subFields = item.get("fields");
            if (nested) {
                if (subFields == null || !subFields.isArray() || subFields.isEmpty()) {
                    errors.add(new Issue(itemPath + ".fields", type + " 必须提供非空 fields 数组"));
                } else {
                    validateFields(subFields, itemPath + ".fields", errors, warnings);
                }
            } else if (subFields != null && !subFields.isNull()) {
                warnings.add(new Issue(itemPath + ".fields", "非 object/arrayofobject 类型不需要 fields"));
            }

            for (String name : fieldNames(item)) {
                if (!Set.of("key", "label", "type", "options", "min", "max", "step", "default",
                        "show", "role", "cardinality", "fields", "description", "placeholder",
                        "unit", "secret", "visibleWhen", "requiredWhen", "map", "maxItems",
                        "section", "help", "advanced", "upstreamKey", "aliases", "injectDefault").contains(name)) {
                    warnings.add(new Issue(itemPath + "." + name, "未知字段，已忽略"));
                }
            }
        }
    }

    private static void checkRole(JsonNode item, String path, List<Issue> errors) {
        String role = text(item, "role");
        if (role != null && !role.isBlank() && !ROLES.contains(role)) {
            errors.add(new Issue(path + ".role",
                    "不支持的角色 " + role + "（可选 image_upload/reference/response）"));
        }
        String cardinality = text(item, "cardinality");
        if ("image_upload".equals(role)) {
            if (cardinality == null || cardinality.isBlank()) {
                errors.add(new Issue(path + ".cardinality", "role=image_upload 时必须指定 single 或 multi"));
            } else if (!CARDINALITIES.contains(cardinality)) {
                errors.add(new Issue(path + ".cardinality", "只能是 single 或 multi"));
            }
        } else if (cardinality != null && !cardinality.isBlank()) {
            errors.add(new Issue(path + ".cardinality", "只有 role=image_upload 时才允许 cardinality"));
        }
    }

    private static void checkOptions(JsonNode item, String path, String type,
                                     List<Issue> errors, List<Issue> warnings) {
        JsonNode options = item.get("options");
        if (!"enum".equals(type)) {
            if (options != null && !options.isNull()) {
                warnings.add(new Issue(path + ".options", "只有 enum 类型使用 options"));
            }
            return;
        }
        if (options == null || !options.isArray() || options.isEmpty()) {
            errors.add(new Issue(path + ".options", "enum 必须提供非空 options 数组"));
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < options.size(); i++) {
            JsonNode option = options.get(i);
            String optionPath = path + ".options[" + i + "]";
            if (option == null || option.isContainerNode() || option.isNull()) {
                errors.add(new Issue(optionPath, "必须是字符串/数字/布尔值"));
            } else if (!seen.add(option.asText())) {
                warnings.add(new Issue(optionPath, "重复的选项：" + option.asText()));
            }
        }
    }

    private static void checkRange(JsonNode item, String path, String type, List<Issue> errors) {
        JsonNode min = item.get("min");
        JsonNode max = item.get("max");
        if (min != null && !min.isNull() && !isNumberLike(min)) {
            errors.add(new Issue(path + ".min", "必须是数字"));
        }
        if (max != null && !max.isNull() && !isNumberLike(max)) {
            errors.add(new Issue(path + ".max", "必须是数字"));
        }
        if (isNumberLike(min) && isNumberLike(max) && min.asDouble() > max.asDouble()) {
            errors.add(new Issue(path + ".min", "min 不能大于 max"));
        }
        if ("range".equals(type) && (min == null || min.isNull()) && (max == null || max.isNull())) {
            errors.add(new Issue(path, "range 类型必须至少提供 min 或 max"));
        }
        JsonNode step = item.get("step");
        if (step != null && !step.isNull() && (!isNumberLike(step) || step.asDouble() <= 0)) {
            errors.add(new Issue(path + ".step", "必须是大于 0 的数字"));
        }
    }

    private static void checkKeyAliases(JsonNode item, String path,
                                        List<Issue> errors, List<Issue> warnings) {
        String key = text(item, "key");
        JsonNode upstreamKey = item.get("upstreamKey");
        if (upstreamKey != null && !upstreamKey.isNull()) {
            if (!upstreamKey.isTextual() || upstreamKey.asText().isBlank()) {
                errors.add(new Issue(path + ".upstreamKey", "必须是非空字符串"));
            } else if (upstreamKey.asText().equals(key)) {
                warnings.add(new Issue(path + ".upstreamKey", "与 key 相同，可省略"));
            }
        }
        JsonNode aliases = item.get("aliases");
        if (aliases != null && !aliases.isNull()) {
            if (!aliases.isArray()) {
                errors.add(new Issue(path + ".aliases", "必须是字符串数组"));
                return;
            }
            Set<String> seen = new LinkedHashSet<>();
            for (int i = 0; i < aliases.size(); i++) {
                JsonNode alias = aliases.get(i);
                String p = path + ".aliases[" + i + "]";
                if (!alias.isTextual() || alias.asText().isBlank()) {
                    errors.add(new Issue(p, "必须是非空字符串"));
                    continue;
                }
                String name = alias.asText();
                if (!seen.add(name)) {
                    warnings.add(new Issue(p, "重复的别名：" + name));
                }
                if (name.equals(key)) {
                    warnings.add(new Issue(p, "别名与 key 相同，可省略"));
                }
            }
        }
    }

    private static void checkConditions(JsonNode item, String path, String key, List<Issue> errors) {        JsonNode node = item.get(key);
        if (node == null || node.isNull()) return;
        if (!node.isObject()) {
            errors.add(new Issue(path + "." + key, "必须是对象，例如 {\"mode\":\"image-to-image\"}"));
            return;
        }
        JsonNode mode = node.get("mode");
        if (mode != null && !mode.isNull() && (!mode.isTextual() || mode.asText().isBlank())) {
            errors.add(new Issue(path + "." + key + ".mode", "必须是非空字符串"));
        }
    }

    private static void checkMap(JsonNode item, String path, List<Issue> errors, List<Issue> warnings) {
        JsonNode map = item.get("map");
        if (map == null || map.isNull()) return;
        if (!map.isObject()) {
            errors.add(new Issue(path + ".map", "必须是对象，例如 {\"2K\":\"2048x2048\"}"));
            return;
        }
        JsonNode options = item.get("options");
        Set<String> optionSet = new LinkedHashSet<>();
        if (options != null && options.isArray()) {
            for (JsonNode option : options) optionSet.add(option.asText());
        }
        for (Map.Entry<String, JsonNode> entry : map.properties()) {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (key == null || key.isBlank()) {
                errors.add(new Issue(path + ".map", "映射键不能为空"));
            } else if (value == null || value.isNull() || value.isContainerNode()) {
                errors.add(new Issue(path + ".map." + key, "映射值必须是字符串/数字/布尔值"));
            }
            if (!optionSet.isEmpty() && !optionSet.contains(key)) {
                warnings.add(new Issue(path + ".map." + key, "映射键不在 options 中"));
            }
        }
    }

    private static void checkMaxItems(JsonNode item, String path, List<Issue> errors) {
        JsonNode maxItems = item.get("maxItems");
        if (maxItems == null || maxItems.isNull()) return;
        if (!maxItems.isInt() || maxItems.asInt() < 1) {
            errors.add(new Issue(path + ".maxItems", "必须是大于 0 的整数"));
        }
    }

    private static void checkDefault(JsonNode item, String path, String type, List<Issue> errors) {
        JsonNode def = item.get("default");
        if (def == null || def.isNull() || type == null) return;

        switch (type) {
            case "enum" -> {
                if (def.isContainerNode()) {
                    errors.add(new Issue(path + ".default", "enum 默认值必须是标量"));
                    return;
                }
                JsonNode options = item.get("options");
                if (options != null && options.isArray()) {
                    boolean matched = false;
                    for (JsonNode option : options) {
                        if (option.asText().equals(def.asText())) {
                            matched = true;
                            break;
                        }
                    }
                    if (!matched) {
                        errors.add(new Issue(path + ".default",
                                "默认值 " + def.asText() + " 不在 options 中"));
                    }
                }
            }
            case "range", "number" -> {
                if (!isNumberLike(def)) {
                    errors.add(new Issue(path + ".default", "必须是数字"));
                    return;
                }
                if ("range".equals(type)) {
                    JsonNode min = item.get("min");
                    JsonNode max = item.get("max");
                    if (isNumberLike(min) && def.asDouble() < min.asDouble()) {
                        errors.add(new Issue(path + ".default", "默认值小于 min"));
                    }
                    if (isNumberLike(max) && def.asDouble() > max.asDouble()) {
                        errors.add(new Issue(path + ".default", "默认值大于 max"));
                    }
                }
            }
            case "bool" -> {
                if (!def.isBoolean()) errors.add(new Issue(path + ".default", "必须是布尔值"));
            }
            case "string" -> {
                if (!def.isTextual()) errors.add(new Issue(path + ".default", "必须是字符串"));
            }
            case "array", "arrayofobject" -> {
                if (!def.isArray()) errors.add(new Issue(path + ".default", "必须是数组"));
            }
            case "object" -> {
                if (!def.isObject()) errors.add(new Issue(path + ".default", "必须是对象"));
            }
            default -> { /* 未知 type 已在别处报错 */ }
        }
    }

    private static void validateResponse(JsonNode response, List<Issue> errors, List<Issue> warnings) {
        if (!response.isObject()) {
            errors.add(new Issue("response", "必须是对象"));
            return;
        }
        boolean hasSync = false;
        boolean hasAsync = false;

        JsonNode sync = response.get("sync");
        if (sync != null && !sync.isNull()) {
            hasSync = true;
            if (!sync.isObject()) {
                errors.add(new Issue("response.sync", "必须是对象"));
            } else {
                validateSync(sync, errors, warnings);
            }
        }

        JsonNode async = response.get("async");
        if (async != null && !async.isNull()) {
            hasAsync = true;
            if (!async.isObject()) {
                errors.add(new Issue("response.async", "必须是对象"));
            } else {
                validateAsync(async, errors, warnings);
            }
        }

        if (!hasSync && !hasAsync) {
            warnings.add(new Issue("response", "既没有 sync 也没有 async，响应会原样透传"));
        }
        for (String name : fieldNames(response)) {
            if (!"sync".equals(name) && !"async".equals(name)) {
                warnings.add(new Issue("response." + name, "未知字段，已忽略"));
            }
        }
    }

    private static void validateSync(JsonNode sync, List<Issue> errors, List<Issue> warnings) {
        checkText(sync, "dataPath", "response.sync", errors, false);
        checkBoolean(sync, "dataIsArray", "response.sync", errors);
        checkBoolean(sync, "itemsAreUrls", "response.sync", errors);
        checkBoolean(sync, "stripDataUri", "response.sync", errors);
        checkText(sync, "mediaField", "response.sync", errors, false);
        checkText(sync, "fallbackField", "response.sync", errors, false);
        checkText(sync, "errorPath", "response.sync", errors, false);
        JsonNode extra = sync.get("extraFields");
        if (extra != null && !extra.isNull()) {
            if (!extra.isArray()) {
                errors.add(new Issue("response.sync.extraFields", "必须是字符串数组"));
            } else {
                for (int i = 0; i < extra.size(); i++) {
                    if (!extra.get(i).isTextual()) {
                        errors.add(new Issue("response.sync.extraFields[" + i + "]", "必须是字符串"));
                    }
                }
            }
        }
        for (String name : fieldNames(sync)) {
            if (!Set.of("dataPath", "dataIsArray", "itemsAreUrls", "mediaField", "fallbackField",
                    "stripDataUri", "errorPath", "extraFields").contains(name)) {
                warnings.add(new Issue("response.sync." + name, "未知字段，已忽略"));
            }
        }
    }

    private static void validateAsync(JsonNode async, List<Issue> errors, List<Issue> warnings) {
        checkText(async, "taskIdPath", "response.async", errors, false);
        checkText(async, "statusPath", "response.async", errors, false);
        checkText(async, "mediaPath", "response.async", errors, false);
        checkText(async, "downloadPath", "response.async", errors, false);
        checkText(async, "errorPath", "response.async", errors, false);
        if (text(async, "statusPath") == null || text(async, "statusPath").isBlank()) {
            warnings.add(new Issue("response.async.statusPath",
                    "未配置时归一化器退回顶层 status，建议显式指定"));
        }

        JsonNode stateMap = async.get("stateMap");
        if (stateMap != null && !stateMap.isNull()) {
            if (!stateMap.isObject()) {
                errors.add(new Issue("response.async.stateMap", "必须是对象"));
            } else {
                for (Map.Entry<String, JsonNode> entry : stateMap.properties()) {
                    String value = entry.getValue().isTextual() ? entry.getValue().asText() : "";
                    String normalized = value.toLowerCase(Locale.ROOT);
                    if (value.isBlank() || !STATUSES.contains(normalized)) {
                        errors.add(new Issue("response.async.stateMap." + entry.getKey(),
                                "必须映射到 " + STATUS_LIST + "，实际为 " + value));
                    }
                }
            }
        }

        String mediaMode = text(async, "mediaMode");
        if (mediaMode != null && !mediaMode.isBlank() && !MEDIA_MODES.contains(mediaMode)) {
            errors.add(new Issue("response.async.mediaMode",
                    "只能是 media_url 或 content_endpoint"));
        }

        checkPositiveNumber(async, "pollIntervalSec", "response.async", errors);
        checkPositiveNumber(async, "timeoutSec", "response.async", errors);

        for (String name : fieldNames(async)) {
            if (!Set.of("taskIdPath", "statusPath", "stateMap", "mediaPath", "mediaMode",
                    "downloadPath", "errorPath", "pollIntervalSec", "timeoutSec").contains(name)) {
                warnings.add(new Issue("response.async." + name, "未知字段，已忽略"));
            }
        }
    }

    private static void checkPositiveNumber(JsonNode node, String key, String path, List<Issue> errors) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) return;
        if (!isNumberLike(value) || value.asDouble() <= 0) {
            errors.add(new Issue(path + "." + key, "必须是大于 0 的数字"));
        }
    }

    private static void checkBoolean(JsonNode node, String key, String path, List<Issue> errors) {
        JsonNode value = node.get(key);
        if (value != null && !value.isNull() && !value.isBoolean()) {
            errors.add(new Issue(path + "." + key, "必须是布尔值"));
        }
    }

    private static void checkText(JsonNode node, String key, String path,
                                  List<Issue> errors, boolean required) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            if (required) errors.add(new Issue(path + "." + key, "不能为空"));
            return;
        }
        if (!value.isTextual()) {
            errors.add(new Issue(path + "." + key, "必须是字符串"));
        } else if (required && value.asText().isBlank()) {
            errors.add(new Issue(path + "." + key, "不能为空"));
        }
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static boolean isNumberLike(JsonNode node) {
        if (node == null || node.isNull()) return false;
        if (node.isNumber()) return true;
        if (!node.isTextual() || node.asText().isBlank()) return false;
        try {
            Double.parseDouble(node.asText().trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String rootMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) return e.getClass().getSimpleName();
        int newline = msg.indexOf('\n');
        return newline > 0 ? msg.substring(0, newline) : msg;
    }

    /** 便于调试/离线检查：把一份 schema 的校验结果打印到 stdout。 */
    public static void main(String[] args) {
        String sample = """
                {"schemaVersion":2,"modes":["text-to-image"],"request":[
                  {"key":"size","label":"尺寸","type":"enum","options":["1024x1024","2K"],"default":"2K","show":true}
                ],"response":{"sync":{"dataPath":"data","mediaField":"b64_json","fallbackField":"url"}}}
                """;
        Result result = validate(sample);
        System.out.println("valid=" + result.valid());
        result.errors().forEach(i -> System.out.println("E " + i.path() + ": " + i.message()));
        result.warnings().forEach(i -> System.out.println("W " + i.path() + ": " + i.message()));
    }
}
