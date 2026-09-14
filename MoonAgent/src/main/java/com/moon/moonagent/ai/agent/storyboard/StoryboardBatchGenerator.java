package com.moon.moonagent.ai.agent.storyboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分镜分批生成协调器：规划总镜头数，按固定范围串行调用模型，再按批顺序合并。
 *
 * <p>该协调器只负责“范围与顺序”，不直接调用 Spring AI，也不做编号归一。这样可独立测试
 * 调用顺序、批次边界和合并行为，且不会通过截断单次模型结果造成分镜丢失。
 */
public final class StoryboardBatchGenerator {

    public static final int DEFAULT_MIN_BATCH_SIZE = 5;
    public static final int DEFAULT_MAX_BATCH_SIZE = 8;
    public static final int DEFAULT_MAX_SCENES = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern EXPLICIT_SCENE_COUNT = Pattern.compile(
            "(?:共|总共|总计|一共|全片)\\s*([0-9]{1,3}|[一二三四五六七八九十百]{1,4})\\s*个?\\s*(?:分镜|镜头)");

    private StoryboardBatchGenerator() {}

    /** 规划结果：总镜头数与每批镜头数。 */
    public record Plan(int totalScenes, int batchSize) {
        public Plan {
            if (totalScenes < 1) throw new IllegalArgumentException("totalScenes 必须大于 0");
            if (batchSize < 1) throw new IllegalArgumentException("batchSize 必须大于 0");
        }

        /** 按顺序切分批次范围，区间两端均包含。 */
        public List<Range> ranges() {
            List<Range> ranges = new ArrayList<>();
            for (int from = 1; from <= totalScenes; from += batchSize) {
                ranges.add(new Range(from, Math.min(totalScenes, from + batchSize - 1)));
            }
            return List.copyOf(ranges);
        }
    }

    /** 单批镜头范围（含起止）。 */
    public record Range(int from, int to) {
        public Range {
            if (from < 1 || to < from) throw new IllegalArgumentException("非法批次范围: " + from + "-" + to);
        }

        public int count() {
            return to - from + 1;
        }
    }

    /** 单次模型调用的原始正文与推理文本。 */
    public record CallOutput(String content, String reasoning) {}

    /** 合并后的分镜与分批推理摘要。 */
    public record Result(List<Map<String, Object>> scenes, String reasoning) {}

    @FunctionalInterface
    public interface BatchCaller {
        CallOutput call(Range range);
    }

    /**
     * 解析规划 JSON，失败时按剧本段落/长度给出确定性兜底。
     * 总镜头数受 {@code maxScenes} 保护，批次大小被钳制到配置允许范围。
     */
    public static Plan parsePlan(String rawPlan, String scriptText,
                                 int minBatchSize, int maxBatchSize, int maxScenes) {
        int min = Math.max(1, minBatchSize);
        int max = Math.max(min, maxBatchSize);
        int sceneLimit = Math.max(1, maxScenes);
        int total = 0;
        int batchSize = 0;
        try {
            JsonNode root = MAPPER.readTree(StoryboardJsonParser.extractJson(rawPlan));
            total = firstPositive(root, "totalScenes", "sceneCount", "total", "count");
            batchSize = firstPositive(root, "batchSize", "batch_size", "perBatch", "per_batch");
        } catch (Exception ignored) {
            // 规划结果不可用时走确定性兜底，不因规划调用格式漂移阻断主流程。
        }
        if (total <= 0) return fallbackPlan(scriptText, min, max, sceneLimit);
        int normalizedBatch = batchSize <= 0 ? defaultBatchSize(min, max) : batchSize;
        Integer explicitCount = detectExplicitSceneCount(scriptText);
        int normalizedTotal = explicitCount == null ? total : explicitCount;
        return new Plan(Math.min(normalizedTotal, sceneLimit), clamp(normalizedBatch, min, max));
    }

    /** 规划调用失败时的确定性兜底：至少 1 镜，长文本按约 600 字/镜估算。 */
    public static Plan fallbackPlan(String scriptText, int minBatchSize, int maxBatchSize, int maxScenes) {
        int min = Math.max(1, minBatchSize);
        int max = Math.max(min, maxBatchSize);
        int sceneLimit = Math.max(1, maxScenes);
        String script = scriptText == null ? "" : scriptText;
        int paragraphs = (int) Arrays.stream(script.split("\\R\\s*\\R"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .count();
        int lengthEstimate = (int) Math.ceil(script.length() / 600.0);
        int estimated = Math.max(1, Math.max(paragraphs, lengthEstimate));
        Integer explicitCount = detectExplicitSceneCount(script);
        int normalizedTotal = explicitCount == null ? estimated : explicitCount;
        return new Plan(Math.min(normalizedTotal, sceneLimit), defaultBatchSize(min, max));
    }

    /**
     * 提取剧本中明确声明的分镜总数，例如「共4个分镜」「总共 5 个镜头」。
     * 显式数量必须覆盖规划模型的估算，避免模型自行增删镜头。
     */
    public static Integer detectExplicitSceneCount(String scriptText) {
        if (scriptText == null || scriptText.isBlank()) return null;
        Matcher matcher = EXPLICIT_SCENE_COUNT.matcher(scriptText);
        while (matcher.find()) {
            int count = parseCount(matcher.group(1));
            if (count > 0) return count;
        }
        return null;
    }

    private static int parseCount(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        if (raw.matches("\\d+")) return Integer.parseInt(raw);

        int total = 0;
        int current = 0;
        for (char c : raw.toCharArray()) {
            int digit = chineseDigit(c);
            if (digit >= 0) {
                current = digit;
            } else if (c == '十') {
                total += (current == 0 ? 1 : current) * 10;
                current = 0;
            } else if (c == '百') {
                total += (current == 0 ? 1 : current) * 100;
                current = 0;
            }
        }
        return total + current;
    }

    private static int chineseDigit(char c) {
        return switch (c) {
            case '一' -> 1;
            case '二' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> -1;
        };
    }

    /**
     * 严格按批次范围顺序调用并合并；解析器沿用分镜宽松解析语义。
     * 任一批无有效分镜时立即抛错，避免悄悄生成缺批结果。
     */
    public static Result generate(Plan plan, Function<String, List<Map<String, Object>>> parser,
                                  BatchCaller caller) {
        validate(plan, parser, caller);

        List<BatchResult> batches = new ArrayList<>();
        for (Range range : plan.ranges()) {
            batches.add(runBatch(range, parser, caller));
        }
        return merge(batches);
    }

    /**
     * 多智能体并行版本：每个 Range 是一个独立 scene writer agent。
     *
     * <p>所有批次同时提交，结果仍按 Range 顺序合并。单个批次失败不会取消其他批次；
     * 汇总所有失败后统一抛错，调用方可以按失败范围单独重试。
     */
    public static Result generateParallel(Plan plan,
                                          Function<String, List<Map<String, Object>>> parser,
                                          BatchCaller caller,
                                          int maxConcurrency) {
        validate(plan, parser, caller);
        List<Range> ranges = plan.ranges();
        int parallelism = Math.min(Math.max(1, maxConcurrency), ranges.size());
        if (parallelism <= 1) return generate(plan, parser, caller);

        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        try {
            List<CompletableFuture<BatchResult>> futures = ranges.stream()
                    .map(range -> CompletableFuture.supplyAsync(
                            () -> runBatch(range, parser, caller), executor))
                    .toList();

            List<BatchResult> ordered = new ArrayList<>(Collections.nCopies(ranges.size(), null));
            Map<Range, String> failures = new LinkedHashMap<>();
            for (int i = 0; i < futures.size(); i++) {
                try {
                    ordered.set(i, futures.get(i).join());
                } catch (CompletionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    failures.put(ranges.get(i), cause.getMessage() == null ? cause.toString() : cause.getMessage());
                }
            }
            if (!failures.isEmpty()) {
                Map.Entry<Range, String> first = failures.entrySet().iterator().next();
                throw new IllegalArgumentException(
                        "并行分镜批次失败: 失败范围=" + failures.keySet()
                                + "，首个错误=第 " + first.getKey().from() + "-" + first.getKey().to()
                                + " 批: " + first.getValue());
            }
            return merge(ordered);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void validate(Plan plan, Function<String, List<Map<String, Object>>> parser,
                                 BatchCaller caller) {
        if (plan == null) throw new IllegalArgumentException("批次计划不能为空");
        if (parser == null || caller == null) throw new IllegalArgumentException("解析器与调用器不能为空");
    }

    private static BatchResult runBatch(Range range,
                                        Function<String, List<Map<String, Object>>> parser,
                                        BatchCaller caller) {
        CallOutput output = caller.call(range);
        if (output == null || output.content() == null || output.content().isBlank()) {
            throw new IllegalArgumentException("第 " + range.from() + "-" + range.to() + " 批未返回内容");
        }
        List<Map<String, Object>> batch;
        try {
            batch = parser.apply(output.content());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "第 " + range.from() + "-" + range.to() + " 批没有有效分镜: " + e.getMessage(), e);
        }
        if (batch == null || batch.isEmpty()) {
            throw new IllegalArgumentException("第 " + range.from() + "-" + range.to() + " 批没有有效分镜");
        }
        return new BatchResult(range, List.copyOf(batch), output.reasoning());
    }

    private static Result merge(List<BatchResult> batches) {
        List<Map<String, Object>> merged = new ArrayList<>();
        StringBuilder reasoning = new StringBuilder();
        for (BatchResult batch : batches) {
            merged.addAll(batch.scenes());
            if (batch.reasoning() != null && !batch.reasoning().isBlank()) {
                if (!reasoning.isEmpty()) reasoning.append("\n");
                reasoning.append("【").append(batch.range().from()).append("-").append(batch.range().to()).append("】")
                        .append(batch.reasoning().trim());
            }
        }
        return new Result(List.copyOf(merged), reasoning.toString());
    }

    private record BatchResult(Range range, List<Map<String, Object>> scenes, String reasoning) {}

    private static int firstPositive(JsonNode root, String... fields) {
        if (root == null || !root.isObject()) return 0;
        for (String field : fields) {
            JsonNode node = root.path(field);
            if (node.isInt() || node.isLong()) return node.asInt(0);
            if (node.isTextual()) {
                try {
                    return Integer.parseInt(node.asText("").replaceAll("[^0-9-]", ""));
                } catch (NumberFormatException ignored) {
                    // 尝试下一个字段
                }
            }
        }
        return 0;
    }

    private static int defaultBatchSize(int min, int max) {
        return clamp((min + max) / 2, min, max);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
