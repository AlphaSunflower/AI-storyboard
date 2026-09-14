package com.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 模型参数 self-describing spec 的响应归一化器（纯静态工具，无 Spring 依赖，可离线自检）。
 *
 * <p>网关按 {@code model_params.params_schema} 里的 {@code response.sync / response.async}，
 * 把上游参差响应统一成 OpenAI 契约：
 * <ul>
 *   <li>视频轮询 → {@code {taskId, status(processing/succeeded/failed), progress?, error?}}</li>
 *   <li>图片同步 → {@code {data:[{b64_json|url}]}}</li>
 * </ul>
 * spec 缺失/解析失败/找不到媒体 → 返回<b>原始响应</b>（调用方透传上游），不吞错误、不误改契约。
 * 未知状态兜底：{@code stateMap} 未命中 → {@code processing}（对齐后端 {@code pollVideoTask}：
 * 非 succeeded/failed 一律视为进行中）。
 */
public final class ModelParamsSpecNormalizer {

    private ModelParamsSpecNormalizer() {}

    public static final ObjectMapper M = new ObjectMapper();

    /** 解析 params_schema → JsonNode；空/非法返回 null（调用方走硬编码兜底/透传）。 */
    public static JsonNode spec(String paramsSchema) {
        if (paramsSchema == null || paramsSchema.isBlank()) return null;
        try {
            JsonNode n = M.readTree(paramsSchema);
            return (n != null && n.isObject()) ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 点路径解析：{@code task.status}/{@code error.message}/{@code content.url}/{@code data.video_url}。 */
    public static JsonNode resolve(String dotPath, JsonNode root) {
        if (dotPath == null || dotPath.isBlank() || root == null) return MissingNode.getInstance();
        JsonNode cur = root;
        for (String seg : dotPath.split("\\.")) {
            if (cur == null || cur.isMissingNode() || cur.isNull()) return MissingNode.getInstance();
            cur = cur.path(seg);
        }
        return cur;
    }

    /**
     * 视频轮询归一化：{@code {taskId, status, progress?, error?}}。
     * {@code spec=null} 时退化为上游顶层 status 三枚举（succeeded/completed→succeeded，failed→failed，其余→processing）。
     * taskId 优先取调用方传入（业务侧已知），否则按 {@code taskIdPath} 从响应提取。
     */
    public static String normalizeVideoPoll(String upstreamBody, JsonNode spec, String taskId) {
        try {
            JsonNode root = M.readTree(upstreamBody);
            JsonNode async = (spec != null) ? spec.path("response").path("async") : null;
            if (async == null || !async.isObject()) {
                return legacyVideoPoll(upstreamBody, taskId);
            }
            String statusRaw = async.path("statusPath").isTextual()
                    ? resolve(async.path("statusPath").asText(""), root).asText("")
                    : root.path("status").asText("");
            String status = mapStatus(async, statusRaw);

            String resolvedTaskId = taskId;
            if ((resolvedTaskId == null || resolvedTaskId.isBlank()) && async.path("taskIdPath").isTextual()) {
                resolvedTaskId = resolve(async.path("taskIdPath").asText(""), root).asText("");
            }

            ObjectNode out = M.createObjectNode();
            if (resolvedTaskId != null && !resolvedTaskId.isBlank()) out.put("taskId", resolvedTaskId);
            out.put("status", status);

            JsonNode prog = root.path("progress");
            if (prog.isNumber() || prog.isTextual()) out.put("progress", prog.asInt());

            if ("failed".equals(status)) {
                String err = async.path("errorPath").isTextual()
                        ? resolve(async.path("errorPath").asText(""), root).asText("")
                        : root.path("error").asText(root.path("message").asText(""));
                out.put("error", err.isBlank() ? "video generation failed" : err);
            } else if ("succeeded".equals(status) && async.path("mediaPath").isTextual()) {
                // 直链模式：把媒体地址带到外部（调用方落 call_log.video_url 供下载取用）
                String media = resolve(async.path("mediaPath").asText(""), root).asText("");
                if (!media.isBlank()) out.put("_mediaUrl", media);
            }
            return M.writeValueAsString(out);
        } catch (Exception e) {
            return upstreamBody;
        }
    }

    /** spec 缺失时的旧行为：顶层 status 三枚举 + task_id/id/taskId 三选一（保持现状零回归）。 */
    private static String legacyVideoPoll(String upstreamBody, String taskId) throws Exception {
        JsonNode root = M.readTree(upstreamBody);
        String raw = root.path("status").asText("");
        String status;
        switch (raw) {
            case "succeeded":
            case "completed": status = "succeeded"; break;
            case "failed": status = "failed"; break;
            default: status = "processing"; break;
        }
        ObjectNode out = M.createObjectNode();
        if (taskId != null && !taskId.isBlank()) out.put("taskId", taskId);
        else {
            String t = root.path("task_id").asText(root.path("id").asText(root.path("taskId").asText("")));
            if (!t.isBlank()) out.put("taskId", t);
        }
        out.put("status", status);
        JsonNode prog = root.path("progress");
        if (prog.isNumber() || prog.isTextual()) out.put("progress", prog.asInt());
        if ("failed".equals(status)) {
            String err = root.path("error").path("message").asText("");
            if (err.isBlank()) err = root.path("error").asText("");
            if (err.isBlank()) err = root.path("message").asText("");
            out.put("error", err.isBlank() ? "video generation failed" : err);
        }
        return M.writeValueAsString(out);
    }

    /** stateMap 映射原始→归一化；未知 → processing（兜底）。 */
    private static String mapStatus(JsonNode async, String raw) {
        if (raw == null || raw.isBlank()) return "processing";
        JsonNode sm = async.path("stateMap");
        if (sm.isObject()) {
            JsonNode mapped = sm.path(raw);
            if (mapped.isTextual() && !mapped.asText().isBlank()) return mapped.asText();
        }
        return "processing";
    }

    /**
     * 图片响应归一化：按 sync spec 定位媒体，重打包 {@code {data:[{b64_json|url}]}}。
     * 找不到媒体（dataIsArray=false 单对象、itemsAreUrls 裸 url 数组、自定义 dataPath）→ 返回原始响应。
     */
    public static String normalizeImageSync(String upstreamBody, JsonNode spec) {
        try {
            JsonNode root = M.readTree(upstreamBody);
            JsonNode sync = (spec != null) ? spec.path("response").path("sync") : null;
            if (sync == null || !sync.isObject()) return upstreamBody;

            String dataPath = sync.path("dataPath").asText("data");
            JsonNode data = resolve(dataPath, root);
            String mediaField = sync.path("mediaField").asText("b64_json");
            String fallbackField = sync.path("fallbackField").asText("url");
            boolean itemsAreUrls = sync.path("itemsAreUrls").asBoolean(false);
            boolean stripDataUri = sync.path("stripDataUri").asBoolean(true);

            ArrayNode images = M.createArrayNode();
            if (itemsAreUrls) {
                if (data.isArray()) for (JsonNode it : data) images.add(it.asText());
                else if (data.isTextual()) images.add(data.asText());
            } else if (data.isArray()) {
                for (JsonNode it : data) {
                    JsonNode img = pickImage(it, mediaField, fallbackField, stripDataUri);
                    if (img != null) images.add(img);
                }
            } else if (data.isObject()) {
                JsonNode img = pickImage(data, mediaField, fallbackField, stripDataUri);
                if (img != null) images.add(img);
            }
            if (images.isEmpty()) return upstreamBody;   // 找不到图 → 透传原始（不吞）

            ObjectNode out = M.createObjectNode();
            out.put("created", root.path("created").asLong(System.currentTimeMillis() / 1000));
            out.set("data", images);
            return M.writeValueAsString(out);
        } catch (Exception e) {
            return upstreamBody;
        }
    }

    /** 从单条媒体项挑图：优先 mediaField，回退 fallbackField；data URI 剥前缀按 b64_json 存。 */
    private static JsonNode pickImage(JsonNode item, String mediaField, String fallbackField, boolean stripDataUri) {
        for (String field : new String[]{mediaField, fallbackField}) {
            JsonNode v = item.path(field);
            if (v.isMissingNode() || v.isNull() || v.asText().isBlank()) continue;
            String val = v.asText();
            if (stripDataUri && val.startsWith("data:")) {
                int comma = val.indexOf(',');
                if (comma >= 0) val = val.substring(comma + 1);
                field = "b64_json";
            }
            ObjectNode o = M.createObjectNode();
            o.put(field, val);
            return o;
        }
        return null;
    }

    // ===== 离线自检（非 Spring 入口；kotlin/mvn 均不执行，仅供单测/人工验证）=====
    public static void selfCheck() {
        String t2v = "{\"task\":{\"task_id\":\"abc\",\"status\":\"PROCESSING\"}}";
        JsonNode s2 = spec("{\"response\":{\"async\":{\"taskIdPath\":\"task.task_id\",\"statusPath\":\"task.status\",\"stateMap\":{\"PENDING\":\"processing\",\"PROCESSING\":\"processing\",\"SUCCESS\":\"succeeded\",\"FAIL\":\"failed\"},\"mediaPath\":\"task.content.url\",\"errorPath\":\"task.error.message\"}}}");
        String r1 = normalizeVideoPoll(t2v, s2, null);
        assert r1.contains("\"taskId\":\"abc\"") && r1.contains("\"status\":\"processing\"") : "t2v-in-progress fail: " + r1;

        String t2vDone = "{\"task\":{\"task_id\":\"abc\",\"status\":\"SUCCESS\",\"content\":{\"url\":\"https://x/v.mp4\"}}}";
        String r2 = normalizeVideoPoll(t2vDone, s2, null);
        assert r2.contains("\"status\":\"succeeded\"") && r2.contains("\"_mediaUrl\":\"https://x/v.mp4\"") : "t2v-success fail: " + r2;

        // 未知状态兜底 → processing
        String t2vOther = "{\"task\":{\"task_id\":\"abc\",\"status\":\"WEIRD\"}}";
        String r3 = normalizeVideoPoll(t2vOther, s2, null);
        assert r3.contains("\"status\":\"processing\"") : "unknown-status fallback fail: " + r3;

        // spec 缺失 → 旧行为（顶层 status）
        String r4 = normalizeVideoPoll("{\"id\":\"z\",\"status\":\"failed\",\"error\":{\"message\":\"boom\"}}", null, null);
        assert r4.contains("\"taskId\":\"z\"") && r4.contains("\"status\":\"failed\"") && r4.contains("\"error\":\"boom\"") : "legacy fallback fail: " + r4;

        // 图：数组 url
        String img = "{\"created\":1,\"data\":[{\"url\":\"http://x/a.png\"},{\"url\":\"http://x/b.png\"}]}";
        JsonNode si = spec("{\"response\":{\"sync\":{\"dataPath\":\"data\",\"mediaField\":\"b64_json\",\"fallbackField\":\"url\",\"stripDataUri\":true}}}");
        String r5 = normalizeImageSync(img, si);
        assert r5.contains("\"data\"") && r5.contains("\"http://x/a.png\"") && r5.contains("\"http://x/b.png\"") : "img url array fail: " + r5;

        // 图：data URI 剥前缀
        String imgB64 = "{\"data\":[{\"url\":\"data:image/png;base64,AAAA\"}]}";
        String r6 = normalizeImageSync(imgB64, si);
        assert r6.contains("\"b64_json\":\"AAAA\"") : "img data-uri strip fail: " + r6;

        // 图：itemsAreUrls 裸 url 串
        JsonNode si2 = spec("{\"response\":{\"sync\":{\"dataPath\":\"images\",\"itemsAreUrls\":true,\"mediaField\":\"x\"}}}");
        String r7 = normalizeImageSync("{\"images\":[\"http://x/1.png\",\"http://x/2.png\"]}", si2);
        assert r7.contains("\"http://x/1.png\"") && r7.contains("\"http://x/2.png\"") : "itemsAreUrls fail: " + r7;

        // 找不到图 → 透传原始
        String r8 = normalizeImageSync("{\"result\":[]}", si);
        assert r8.equals("{\"result\":[]}") : "no-image passthrough fail: " + r8;

        System.out.println("ModelParamsSpecNormalizer.selfCheck: ALL PASS");
    }

    public static void main(String[] args) { selfCheck(); }
}
