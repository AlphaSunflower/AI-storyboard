package com.storyboard.common.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Doubao Seedream 图片生成协议工具。
 *
 * <p>仅封装与厂商协议直接相关的纯逻辑，供主后端与 agent-core 复用；网络调用和对象存储仍由各服务的
 * {@code ImageGenerationService} 负责。</p>
 */
public final class SeedreamImageProtocol {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> SIZE_TIERS = List.of("1K", "2K", "4K");
    private static final int MAX_INPUT_IMAGES = 14;
    private static final int MAX_TOTAL_IMAGES = 15;
    private static final Pattern ARABIC_COUNT = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*张");
    private static final Pattern CHINESE_COUNT = Pattern.compile(
            "(两|二|三|四|五|六|七|八|九|十|十一|十二|十三|十四|十五)\\s*张");
    private static final java.util.Map<String, Integer> CN_NUMBERS = java.util.Map.ofEntries(
            java.util.Map.entry("两", 2), java.util.Map.entry("二", 2), java.util.Map.entry("三", 3),
            java.util.Map.entry("四", 4), java.util.Map.entry("五", 5), java.util.Map.entry("六", 6),
            java.util.Map.entry("七", 7), java.util.Map.entry("八", 8), java.util.Map.entry("九", 9),
            java.util.Map.entry("十", 10), java.util.Map.entry("十一", 11), java.util.Map.entry("十二", 12),
            java.util.Map.entry("十三", 13), java.util.Map.entry("十四", 14), java.util.Map.entry("十五", 15));

    private SeedreamImageProtocol() {
    }

    /** 是否为使用方舟图片生成协议的 Seedream 模型。 */
    public static boolean isSeedream(String model) {
        return model != null && model.startsWith("doubao-seedream-");
    }

    /** 归一化分辨率档位；非 1K/2K/4K 的值统一回退到官方推荐默认值 2K。 */
    public static String normalizeSize(String size) {
        if (size == null || size.isBlank()) return "2K";
        String normalized = size.trim().toUpperCase(Locale.ROOT);
        return SIZE_TIERS.contains(normalized) ? normalized : "2K";
    }

    /**
     * 构建方舟 {@code /images/generations} 请求。
     *
     * <p>n&gt;1 时显式限制组图张数；n=1 时禁用组图；n=0 时交给模型自动决定张数。</p>
     */
    public static ObjectNode buildRequest(String model, String prompt, List<String> images,
                                          String size, Integer n) {
        boolean autoCount = n != null && n == 0;
        int requestedCount = autoCount ? 0 : resolveRequestedCount(prompt, n);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("prompt", autoCount ? prompt : withGroupInstruction(prompt, requestedCount));
        body.put("size", normalizeSize(size));
        body.put("sequential_image_generation", autoCount || requestedCount > 1 ? "auto" : "disabled");

        if (!autoCount && requestedCount > 1) {
            body.putObject("sequential_image_generation_options").put("max_images", requestedCount);
        }

        if (images != null && !images.isEmpty()) {
            if (images.size() == 1) {
                body.put("image", images.getFirst());
            } else {
                ArrayNode imageArray = body.putArray("image");
                images.forEach(imageArray::add);
            }
        }

        body.put("stream", true);
        body.put("response_format", "url");
        body.put("watermark", false);
        return body;
    }

    /** 显式 n 优先且不再从提示词推断；未传 n 时才从提示词推断组图数量。 */
    public static int resolveRequestedCount(String prompt, Integer n) {
        if (n != null) return Math.min(Math.max(n, 1), MAX_TOTAL_IMAGES);
        int inferred = inferCount(prompt);
        return inferred > 1 ? inferred : 1;
    }

    private static int inferCount(String prompt) {
        if (prompt == null || prompt.isBlank()) return 1;
        java.util.regex.Matcher arabic = ARABIC_COUNT.matcher(prompt);
        if (arabic.find()) {
            int value = Integer.parseInt(arabic.group(1));
            if (value >= 1 && value <= MAX_TOTAL_IMAGES) return value;
        }
        java.util.regex.Matcher chinese = CHINESE_COUNT.matcher(prompt);
        if (chinese.find()) {
            return CN_NUMBERS.getOrDefault(chinese.group(1), 1);
        }
        return 1;
    }

    /** max_images 只是上限；n>1 时补一句明确组图指令，确保模型实际输出目标张数。 */
    private static String withGroupInstruction(String prompt, Integer n) {
        if (n == null || n <= 1) return prompt;
        String instruction = "生成一组 " + n
                + " 张内容关联、整体风格统一的图片；每张在构图、视角或场景细节上必须有明显差异，不要输出重复或近似图片。";
        if (Pattern.compile("(?:生成|输出|给出)\\s*(?:一组|一套)?\\s*" + n + "\\s*张")
                .matcher(prompt == null ? "" : prompt)
                .find()) {
            return prompt;
        }
        return (prompt == null ? "" : prompt.stripTrailing()) + "\n\n" + instruction;
    }

    /** 校验参考图数量及“输入图 + 显式组图输出”的总量限制。 */
    public static void validateLimits(int inputImageCount, int requestedOutputCount) {
        if (inputImageCount < 0 || requestedOutputCount < 0) {
            throw new IllegalArgumentException("图片数量不能为负数");
        }
        if (inputImageCount > MAX_INPUT_IMAGES) {
            throw new IllegalArgumentException("Seedream 最多支持 14 张参考图");
        }
        if (requestedOutputCount > MAX_TOTAL_IMAGES) {
            throw new IllegalArgumentException("Seedream 单次最多生成 15 张图片");
        }
        if (requestedOutputCount > 1 && inputImageCount + requestedOutputCount > MAX_TOTAL_IMAGES) {
            throw new IllegalArgumentException("输入参考图数量与生成图片数量之和不能超过 15 张");
        }
    }

    /** 解析一个完整 SSE 事件块；忽略空块和非 data 行。 */
    public static Event parseEvent(String block) {
        if (block == null || block.isBlank()) {
            return Event.unknown();
        }
        StringBuilder data = new StringBuilder();
        for (String rawLine : block.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = rawLine.strip();
            if (!line.startsWith("data:")) continue;
            if (!data.isEmpty()) data.append('\n');
            data.append(line.substring(5).stripLeading());
        }
        if (data.isEmpty()) {
            return Event.unknown();
        }

        try {
            JsonNode node = MAPPER.readTree(data.toString());
            String type = node.path("type").asText("");
            if ("image_generation.partial_succeeded".equals(type)) {
                return new Event(EventType.PARTIAL_SUCCEEDED, node.path("image_index").asInt(-1),
                        node.path("url").asText(null), node.path("b64_json").asText(null),
                        node.path("size").asText(null), null, null, null);
            }
            if ("image_generation.partial_failed".equals(type)) {
                JsonNode error = node.path("error");
                return new Event(EventType.PARTIAL_FAILED, node.path("image_index").asInt(-1),
                        null, null, null, error.path("code").asText(null),
                        error.path("message").asText(null), null);
            }
            if ("image_generation.completed".equals(type)) {
                return new Event(EventType.COMPLETED, -1, null, null, null,
                        null, null, node.path("usage"));
            }
            JsonNode error = node.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                return new Event(EventType.ERROR, -1, null, null, null,
                        error.path("code").asText(null), error.path("message").asText(null), null);
            }
            return Event.unknown();
        } catch (Exception ignored) {
            return Event.unknown();
        }
    }

    /** 从 SSE 输入流逐事件读取；空行分帧，EOF 时处理最后一个未结束的帧。 */
    public static void readEvents(InputStream input, Consumer<Event> consumer) throws IOException {
        if (input == null || consumer == null) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder frame = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    emitFrame(frame, consumer);
                    continue;
                }
                if (!frame.isEmpty()) frame.append('\n');
                frame.append(line);
            }
            emitFrame(frame, consumer);
        }
    }

    private static void emitFrame(StringBuilder frame, Consumer<Event> consumer) {
        if (frame.isEmpty()) return;
        Event event = parseEvent(frame.toString());
        frame.setLength(0);
        if (event.type() != EventType.UNKNOWN) {
            consumer.accept(event);
        }
    }

    public enum EventType {
        PARTIAL_SUCCEEDED,
        PARTIAL_FAILED,
        COMPLETED,
        ERROR,
        UNKNOWN
    }

    public record Event(EventType type, int imageIndex, String url, String b64Json,
                        String size, String code, String message, JsonNode usage) {

        public static Event unknown() {
            return new Event(EventType.UNKNOWN, -1, null, null, null, null, null, null);
        }
    }
}
