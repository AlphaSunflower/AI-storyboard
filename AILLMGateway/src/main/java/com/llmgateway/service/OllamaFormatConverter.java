package com.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OpenAI chat/completions 格式 ↔ Ollama /api/chat 格式互转（含流式 chunk）。
 * <p>
 * 纯 JSON 转换，网络/DB 归调用方（OllamaRouteAdapter）；images 的远程 URL 由 {@link ImageResolver} 解出 base64。
 * 请求侧：客户端显式值优先，其次 {@code defaults}（model_params 兜底默认，已由适配器解析好）。
 */
@Component
public class OllamaFormatConverter {

    /** 解析 image_url → base64 字符串（data: 去前缀 / 远程下载 / 其它抛错）。 */
    @FunctionalInterface
    public interface ImageResolver {
        String resolve(String url) throws Exception;
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * OpenAI chat 请求 → Ollama /api/chat 请求。
     *
     * @param openAiBody   OpenAI 风格 JSON
     * @param defaults     兜底默认（temperature/max_tokens/top_p/think/think_level/num_ctx/seed/stop/keep_alive/format），客户端显式值优先
     */
    public String toOllamaChatRequest(String openAiBody, Map<String, Object> defaults,
                                      ImageResolver imageResolver) throws Exception {
        JsonNode src = objectMapper.readTree(openAiBody);
        ObjectNode out = objectMapper.createObjectNode();
        out.put("model", src.path("model").asText(""));
        out.set("messages", toOllamaMessages(src.path("messages"), imageResolver));
        out.put("stream", src.path("stream").asBoolean(false));

        // options：客户端值优先，缺失用 defaults 兜底
        ObjectNode options = objectMapper.createObjectNode();
        putDouble(options, "temperature", src, defaults, "temperature");
        putDouble(options, "top_p", src, defaults, "top_p");
        putInt(options, "num_predict", src, defaults, "max_tokens");
        putInt(options, "num_ctx", src, defaults, "num_ctx");
        putInt(options, "seed", src, defaults, "seed");
        putDouble(options, "presence_penalty", src, defaults, "presence_penalty");
        putDouble(options, "frequency_penalty", src, defaults, "frequency_penalty");
        putStop(options, src, defaults);
        if (!options.isEmpty()) out.set("options", options);

        // think：客户端 think/think_level 优先，缺失用 defaults；true+level → 发等级字符串
        putThink(out, src, defaults);
        // tools / format / keep_alive 透传
        if (src.hasNonNull("tools")) out.set("tools", src.get("tools"));
        putFormat(out, src, defaults);
        String keepAlive = src.hasNonNull("keep_alive") ? src.get("keep_alive").asText()
                : defaults.get("keep_alive") == null ? null : String.valueOf(defaults.get("keep_alive"));
        if (keepAlive != null && !keepAlive.isBlank()) out.put("keep_alive", keepAlive);

        return objectMapper.writeValueAsString(out);
    }

    private ArrayNode toOllamaMessages(JsonNode messages, ImageResolver resolver) throws Exception {
        ArrayNode arr = objectMapper.createArrayNode();
        if (!messages.isArray()) return arr;
        for (JsonNode m : messages) {
            ObjectNode msg = objectMapper.createObjectNode();
            msg.put("role", m.path("role").asText("user"));
            List<String> texts = new ArrayList<>();
            List<String> images = new ArrayList<>();
            JsonNode content = m.get("content");
            if (content != null && content.isArray()) {
                for (JsonNode part : content) {
                    String type = part.path("type").asText("");
                    if ("image_url".equals(type)) {
                        String url = part.path("image_url").path("url").asText("");
                        images.add(resolver.resolve(url));
                    } else if (part.hasNonNull("text")) {
                        texts.add(part.path("text").asText(""));
                    }
                }
                msg.put("content", String.join("\n", texts));
            } else {
                msg.put("content", content == null ? "" : content.asText(""));
            }
            if (!images.isEmpty()) {
                ArrayNode imgArr = msg.putArray("images");
                images.forEach(imgArr::add);
            }
            arr.add(msg);
        }
        return arr;
    }

    private void putDouble(ObjectNode options, String key, JsonNode src, Map<String, Object> defaults, String srcKey) {
        if (src.hasNonNull(srcKey)) {
            options.put(key, src.get(srcKey).asDouble());
        } else if (defaults.get(srcKey) instanceof Number n) {
            options.put(key, n.doubleValue());
        }
    }

    private void putInt(ObjectNode options, String key, JsonNode src, Map<String, Object> defaults, String srcKey) {
        if (src.hasNonNull(srcKey)) {
            options.put(key, src.get(srcKey).asInt());
        } else if (defaults.get(srcKey) instanceof Number n) {
            options.put(key, n.intValue());
        }
    }

    private void putStop(ObjectNode options, JsonNode src, Map<String, Object> defaults) {
        List<String> stop;
        if (src.hasNonNull("stop")) {
            stop = toList(src.get("stop"));
        } else if (defaults.get("stop") instanceof List<?> list) {
            stop = new ArrayList<>();
            for (Object o : list) stop.add(String.valueOf(o));
        } else {
            return;
        }
        if (stop != null && !stop.isEmpty()) {
            ArrayNode arr = options.putArray("stop");
            stop.forEach(arr::add);
        }
    }

    private void putThink(ObjectNode out, JsonNode src, Map<String, Object> defaults) {
        Object think;
        if (src.hasNonNull("think")) {
            think = src.get("think").isBoolean() ? src.get("think").asBoolean() : src.get("think").asText();
        } else {
            think = defaults.get("think");
        }
        String thinkLevel = src.hasNonNull("think_level") ? src.get("think_level").asText()
                : defaults.get("think_level") == null ? "" : String.valueOf(defaults.get("think_level"));
        if (think instanceof Boolean b) {
            if (b) {
                if (!thinkLevel.isBlank()) out.put("think", thinkLevel);
                else out.put("think", true);
            } else {
                out.put("think", false);
            }
        } else if (think instanceof String s && !s.isBlank()) {
            out.put("think", s);
        }
    }

    private void putFormat(ObjectNode out, JsonNode src, Map<String, Object> defaults) {
        JsonNode fmt = null;
        if (src.hasNonNull("format")) {
            fmt = src.get("format");
        } else if (src.hasNonNull("response_format")
                && "json_object".equals(src.path("response_format").path("type").asText(""))) {
            out.put("format", "json");
            return;
        } else if (defaults.get("format") != null) {
            String f = String.valueOf(defaults.get("format"));
            if (!f.isBlank()) out.put("format", f);
            return;
        }
        if (fmt != null) {
            if (fmt.isTextual() && !fmt.asText().isBlank()) out.put("format", fmt.asText());
            else if (fmt.isObject()) out.set("format", fmt);
        }
    }

    private List<String> toList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode n : node) list.add(n.asText());
        } else if (node.isTextual() && !node.asText().isBlank()) {
            list.add(node.asText());
        }
        return list;
    }

    /** Ollama /api/chat 响应 → OpenAI chat.completion。 */
    public String toOpenAiChatResponse(String ollamaRaw, String model) throws Exception {
        JsonNode src = objectMapper.readTree(ollamaRaw);
        JsonNode message = src.path("message");
        ObjectNode out = objectMapper.createObjectNode();
        out.put("id", newCompletionId());
        out.put("object", "chat.completion");
        out.put("created", System.currentTimeMillis() / 1000);
        out.put("model", model == null || model.isBlank() ? src.path("model").asText("") : model);

        ArrayNode choices = out.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        ObjectNode msg = choice.putObject("message");
        msg.put("role", "assistant");
        msg.put("content", message.path("content").asText(""));
        if (message.hasNonNull("thinking")) msg.put("reasoning_content", message.path("thinking").asText(""));
        if (message.hasNonNull("tool_calls")) msg.set("tool_calls", toOpenAiToolCalls(message.path("tool_calls")));
        choice.put("finish_reason", src.path("done").asBoolean(false) ? "stop" : "stop");

        ObjectNode usage = out.putObject("usage");
        int prompt = src.path("prompt_eval_count").asInt(0);
        int completion = src.path("eval_count").asInt(0);
        usage.put("prompt_tokens", prompt);
        usage.put("completion_tokens", completion);
        usage.put("total_tokens", prompt + completion);
        return objectMapper.writeValueAsString(out);
    }

    /** 单行 NDJSON → OpenAI SSE chunk JSON（不含 data: 前缀，由调用方包裹）。 */
    public String toChunkJson(JsonNode line, String id, String model) throws Exception {
        JsonNode message = line.path("message");
        ObjectNode out = objectMapper.createObjectNode();
        out.put("id", id);
        out.put("object", "chat.completion.chunk");
        out.put("created", System.currentTimeMillis() / 1000);
        out.put("model", model);
        ArrayNode choices = out.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        ObjectNode delta = choice.putObject("delta");
        String content = message.path("content").asText("");
        String thinking = message.path("thinking").asText("");
        if (!content.isBlank()) delta.put("content", content);
        if (!thinking.isBlank()) delta.put("reasoning_content", thinking);
        if (message.hasNonNull("tool_calls")) delta.set("tool_calls", toOpenAiToolCalls(message.path("tool_calls")));
        choice.put("finish_reason", line.path("done").asBoolean(false) ? "stop" : null);
        return objectMapper.writeValueAsString(out);
    }

    public String newCompletionId() {
        return "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private ArrayNode toOpenAiToolCalls(JsonNode ollamaToolCalls) {
        ArrayNode arr = objectMapper.createArrayNode();
        if (!ollamaToolCalls.isArray()) return arr;
        int i = 0;
        for (JsonNode call : ollamaToolCalls) {
            ObjectNode tc = arr.addObject();
            tc.put("id", "call_" + (i++));
            tc.put("type", "function");
            ObjectNode fn = tc.putObject("function");
            fn.put("name", call.path("function").path("name").asText(""));
            JsonNode args = call.path("function").path("arguments");
            fn.put("arguments", args.isMissingNode() || args.isNull() ? "{}" : args.toString());
        }
        return arr;
    }
}
