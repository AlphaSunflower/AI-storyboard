package com.storyboard.common.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Doubao Seedream 图片生成协议工具。
 *
 * <p>仅封装与厂商协议直接相关的纯逻辑，供主后端与 agent-core 复用；网络调用和对象存储仍由各服务的
 * {@code ImageGenerationService} 负责。</p>
 *
 * <p>方舟对参数的支持是<b>按模型</b>划分的，发错一个字段直接 400（InvalidParameter）。典型差异：</p>
 * <ul>
 *   <li>Seedream 5.0 pro / 5.0 flash 只出单图，<b>不能携带</b> {@code sequential_image_generation}
 *       和 {@code sequential_image_generation_options}，且<b>不支持流式输出</b>（不能传 {@code stream}）。</li>
 *   <li>尺寸档位按模型区分：5.0 pro/flash 为 1K/1.5K/2K，5.0 lite 为 2K/3K/4K，4.5 为 2K/4K，4.0 为 1K/2K/4K。</li>
 *   <li>参考图上限：5.0 pro/flash 为 10 张，其余为 14 张。</li>
 * </ul>
 *
 * @see <a href="https://docs.volcengine.com/docs/ark/image-generation-api?lang=zh">方舟图片生成 API</a>
 */
public final class SeedreamImageProtocol {

    private static final Logger log = LoggerFactory.getLogger(SeedreamImageProtocol.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> SIZE_TIERS = List.of("1K", "2K", "4K");
    /** 档位由低到高，用于把模型不支持的尺寸就近降级。 */
    private static final List<String> TIER_ORDER = List.of("1K", "1.5K", "2K", "3K", "4K");
    private static final String DEFAULT_SIZE_TIER = "2K";
    private static final int MAX_INPUT_IMAGES = 14;
    private static final int MAX_TOTAL_IMAGES = 15;

    /** Seedream 5.0 pro / 5.0 flash：单图模型，不支持组图与流式输出，最多 10 张参考图，尺寸仅 1K/1.5K/2K。 */
    private static final Capabilities SINGLE_IMAGE_CAPABILITIES =
            new Capabilities(false, false, List.of("1K", "1.5K", "2K"), 10);
    /** Seedream 5.0 lite（含别名 doubao-seedream-5-0-&lt;版本号&gt;）：组图 + 流式，尺寸为 2K/3K/4K。 */
    private static final Capabilities LITE_CAPABILITIES =
            new Capabilities(true, true, List.of("2K", "3K", "4K"), MAX_INPUT_IMAGES);
    /** Seedream 4.5：组图 + 流式，尺寸仅 2K/4K。 */
    private static final Capabilities V45_CAPABILITIES =
            new Capabilities(true, true, List.of("2K", "4K"), MAX_INPUT_IMAGES);
    /** Seedream 4.0 及未知 Seedream 模型：与历史实现一致（组图 + 流式 + 1K/2K/4K）。 */
    private static final Capabilities GROUP_CAPABILITIES =
            new Capabilities(true, true, SIZE_TIERS, MAX_INPUT_IMAGES);

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

    /** 模型能力：是否支持组图、是否支持流式输出、可选尺寸档位、参考图上限。 */
    public record Capabilities(boolean groupImages, boolean streaming,
                               List<String> sizeTiers, int maxInputImages) {
    }

    /** 按模型 ID 解析方舟能力；未知 Seedream 模型按 4.0 的历史行为兜底。 */
    public static Capabilities capabilities(String model) {
        if (model == null || model.isBlank()) return GROUP_CAPABILITIES;
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("doubao-seedream-5-0-pro")
                || normalized.startsWith("doubao-seedream-5-0-flash")) {
            return SINGLE_IMAGE_CAPABILITIES;
        }
        if (normalized.startsWith("doubao-seedream-5-0")) {
            return LITE_CAPABILITIES;
        }
        if (normalized.startsWith("doubao-seedream-4-5")) {
            return V45_CAPABILITIES;
        }
        return GROUP_CAPABILITIES;
    }

    /** 模型是否支持组图（{@code sequential_image_generation}）。 */
    public static boolean supportsGroupImages(String model) {
        return capabilities(model).groupImages();
    }

    /** 模型是否支持流式输出（SSE）。不支持时请求体不得携带 {@code stream}。 */
    public static boolean supportsStreaming(String model) {
        return capabilities(model).streaming();
    }

    /** 归一化分辨率档位；模型不支持的档位就近降级，非档位值回退模型默认档位。 */
    public static String normalizeSize(String model, String size) {
        Capabilities caps = capabilities(model);
        String requested = size == null ? "" : size.trim().toUpperCase(Locale.ROOT);
        if (caps.sizeTiers().contains(requested)) return requested;
        String fallback = caps.sizeTiers().contains(DEFAULT_SIZE_TIER)
                ? DEFAULT_SIZE_TIER : caps.sizeTiers().getFirst();
        if (requested.isBlank() || !TIER_ORDER.contains(requested)) return fallback;
        String nearest = caps.sizeTiers().stream()
                .min(Comparator.comparingDouble((String tier) -> Math.abs(tierValue(tier) - tierValue(requested)))
                        .thenComparingDouble(SeedreamImageProtocol::tierValue))
                .orElse(fallback);
        log.info("模型 {} 不支持尺寸档位 {}，降级为 {}", model, requested, nearest);
        return nearest;
    }

    /** 兼容旧签名：按组图模型（1K/2K/4K）的档位集合归一化。 */
    public static String normalizeSize(String size) {
        return normalizeSize(null, size);
    }

    private static double tierValue(String tier) {
        String digits = tier.endsWith("K") ? tier.substring(0, tier.length() - 1) : tier;
        try {
            return Double.parseDouble(digits);
        } catch (NumberFormatException e) {
            return Double.MAX_VALUE;
        }
    }

    /**
     * 构建方舟 {@code /images/generations} 请求。
     *
     * <p>参数按模型能力裁剪：不支持组图的模型完全不写 {@code sequential_image_generation} /
     * {@code sequential_image_generation_options}；不支持流式输出的模型不写 {@code stream}（默认即非流式）。
     * n&gt;1 时显式限制组图张数；n=1 时禁用组图；n=0 时交给模型自动决定张数。</p>
     */
    public static ObjectNode buildRequest(String model, String prompt, List<String> images,
                                          String size, Integer n) {
        Capabilities caps = capabilities(model);
        boolean autoCount = caps.groupImages() && n != null && n == 0;
        int requestedCount = autoCount ? 0 : resolveRequestedCount(model, prompt, n);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("prompt", autoCount ? prompt : withGroupInstruction(prompt, requestedCount));
        body.put("size", normalizeSize(model, size));

        if (caps.groupImages()) {
            body.put("sequential_image_generation", autoCount || requestedCount > 1 ? "auto" : "disabled");
            if (!autoCount && requestedCount > 1) {
                body.putObject("sequential_image_generation_options").put("max_images", requestedCount);
            }
        }

        if (images != null && !images.isEmpty()) {
            if (images.size() == 1) {
                body.put("image", images.getFirst());
            } else {
                ArrayNode imageArray = body.putArray("image");
                images.forEach(imageArray::add);
            }
        }

        if (caps.streaming()) {
            body.put("stream", true);
        }
        body.put("response_format", "url");
        body.put("watermark", false);
        return body;
    }

    /**
     * 按模型能力解析本次请求实际生成的图片张数。
     *
     * <p>显式 n 优先且不再从提示词推断；未传 n 时才从提示词推断组图数量。单图模型恒为 1，
     * 传入更大的 n 只记日志不报错（方舟侧没有 {@code n} 参数，多图只能靠组图能力）。</p>
     */
    public static int resolveRequestedCount(String model, String prompt, Integer n) {
        if (!capabilities(model).groupImages()) {
            if (n != null && n > 1) {
                log.info("模型 {} 仅支持单图，已忽略请求的 {} 张", model, n);
            }
            return 1;
        }
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

    /** 校验参考图数量及“输入图 + 显式组图输出”的总量限制；单图模型忽略输出张数。 */
    public static void validateLimits(String model, int inputImageCount, int requestedOutputCount) {
        if (inputImageCount < 0 || requestedOutputCount < 0) {
            throw new IllegalArgumentException("图片数量不能为负数");
        }
        Capabilities caps = capabilities(model);
        if (inputImageCount > caps.maxInputImages()) {
            throw new IllegalArgumentException("Seedream 最多支持 " + caps.maxInputImages() + " 张参考图");
        }
        if (!caps.groupImages()) return;
        if (requestedOutputCount > MAX_TOTAL_IMAGES) {
            throw new IllegalArgumentException("Seedream 单次最多生成 15 张图片");
        }
        if (requestedOutputCount > 1 && inputImageCount + requestedOutputCount > MAX_TOTAL_IMAGES) {
            throw new IllegalArgumentException("输入参考图数量与生成图片数量之和不能超过 15 张");
        }
    }

    /** 兼容旧签名：按组图模型（14 张参考图 / 15 张总量）校验。 */
    public static void validateLimits(int inputImageCount, int requestedOutputCount) {
        validateLimits(null, inputImageCount, requestedOutputCount);
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

    /**
     * 解析非流式响应的 JSON 体，产出与 SSE 同构的事件序列。
     *
     * <p>方舟非流式响应为 {@code {created, model, data:[{url|b64_json|error}], usage}}；
     * 顶层 {@code error} 直接产出一条 {@link EventType#ERROR}。</p>
     */
    public static List<Event> parseSyncResponse(String json) {
        List<Event> events = new ArrayList<>();
        JsonNode node;
        try {
            node = MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            events.add(new Event(EventType.ERROR, -1, null, null, null, null,
                    "无法解析图片生成响应: " + e.getMessage(), null));
            return events;
        }
        JsonNode error = node.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            events.add(new Event(EventType.ERROR, -1, null, null, null,
                    error.path("code").asText(null), error.path("message").asText(null), null));
            return events;
        }
        JsonNode data = node.path("data");
        if (data.isArray()) {
            int index = 0;
            for (JsonNode item : data) {
                JsonNode itemError = item.path("error");
                if (!itemError.isMissingNode() && !itemError.isNull()) {
                    events.add(new Event(EventType.PARTIAL_FAILED, index, null, null, null,
                            itemError.path("code").asText(null), itemError.path("message").asText(null), null));
                } else {
                    events.add(new Event(EventType.PARTIAL_SUCCEEDED, index,
                            item.path("url").asText(null), item.path("b64_json").asText(null),
                            item.path("size").asText(null), null, null, null));
                }
                index++;
            }
        }
        events.add(new Event(EventType.COMPLETED, -1, null, null, null,
                null, null, node.path("usage")));
        return events;
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
