package com.moon.moonagent.ai.storyboard.production;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 分镜字段的内置枚举字典（景别 / 运镜）。
 *
 * <p>为什么需要它：模型每次都会「重新发明」运镜与景别的说法——同一部片里同时出现
 * 「推近 / 缓推 / 缓慢推进 / 镜头缓慢向前推进」，前端筛选、跨镜风格对齐、提示词拼接全都对不上。
 * 字典把取值收成一小组标准词，模型只能从里面挑。
 *
 * <p>但不做死：{@link #normalizeCameraMovement} 等识别不了时返回 {@code null}，
 * 由调用方决定「保留用户/模型的自定义写法（≤10 字）」还是「退回默认值」——
 * 「希区柯克变焦」这类特殊表达不该被字典掐掉。
 */
public final class StoryboardLexicon {

    /** 景别标准词（由远到近）。 */
    public static final List<String> SHOT_TYPES = List.of(
            "大远景", "远景", "全景", "中景", "近景", "特写", "大特写");

    /** 运镜标准词。 */
    public static final List<String> CAMERA_MOVEMENTS = List.of(
            "固定", "推近", "拉远", "左摇", "右摇", "横移", "跟拍", "升镜", "降镜", "环绕", "手持");

    public static final String DEFAULT_SHOT_TYPE = "中景";
    public static final String DEFAULT_CAMERA_MOVEMENT = "固定";

    /** 自定义写法允许的最大长度：再长就不是「运镜」而是句子了。 */
    public static final int MAX_CUSTOM_LENGTH = 10;

    /** 常用否定词表：模型不写或写歪时由代码补齐，禁止模型每次自己发明。 */
    /**
     * 默认否定词表：分两段——基础画质词 + {@link StoryboardPromptGuides#ANTI_SLOP_TERMS}
     * 的「廉价质感」词。模型最容易加的就是后者，加完画面直接掉档。
     */
    public static final List<String> DEFAULT_NEGATIVE_PROMPTS = java.util.stream.Stream.concat(
                    List.of("模糊", "畸变", "多余文字", "水印", "logo", "形变", "多余肢体",
                            "低清晰度", "噪点").stream(),
                    StoryboardPromptGuides.ANTI_SLOP_TERMS.stream())
            .distinct()
            .toList();

    /** 景别别名：模型常见写法 → 标准词（按 contains 匹配，优先长词）。 */
    private static final Map<String, String> SHOT_ALIASES = aliases(Map.ofEntries(
            Map.entry("大远景", "大远景"),
            Map.entry("大全景", "远景"),
            Map.entry("远景", "远景"),
            Map.entry("全景", "全景"),
            Map.entry("中景", "中景"),
            Map.entry("中近景", "近景"),
            Map.entry("近景", "近景"),
            Map.entry("特写", "特写"),
            Map.entry("大特写", "大特写")));

    /** 运镜别名：同样按 contains 匹配，优先长词（「缓慢推近」要落在「推近」而不是「推」）。 */
    private static final Map<String, String> MOVEMENT_ALIASES = aliases(Map.ofEntries(
            Map.entry("固定镜头", "固定"),
            Map.entry("固定机位", "固定"),
            Map.entry("静止", "固定"),
            Map.entry("固定", "固定"),
            Map.entry("推近", "推近"),
            Map.entry("推进", "推近"),
            Map.entry("前推", "推近"),
            Map.entry("缓推", "推近"),
            Map.entry("推", "推近"),
            Map.entry("拉远", "拉远"),
            Map.entry("后拉", "拉远"),
            Map.entry("拉镜", "拉远"),
            Map.entry("拉", "拉远"),
            Map.entry("左摇", "左摇"),
            Map.entry("右摇", "右摇"),
            Map.entry("横移", "横移"),
            Map.entry("平移", "横移"),
            Map.entry("跟拍", "跟拍"),
            Map.entry("跟随", "跟拍"),
            Map.entry("升镜", "升镜"),
            Map.entry("上升", "升镜"),
            Map.entry("降镜", "降镜"),
            Map.entry("下降", "降镜"),
            Map.entry("环绕", "环绕"),
            Map.entry("手持", "手持")));

    private StoryboardLexicon() {
    }

    /**
     * 归一景别。
     *
     * @return 标准词；无法识别返回 {@code null}（调用方决定保留自定义还是用默认值）
     */
    public static String normalizeShotType(String raw) {
        return match(SHOT_TYPES, SHOT_ALIASES, raw);
    }

    /**
     * 归一运镜。
     *
     * @return 标准词；无法识别返回 {@code null}
     */
    public static String normalizeCameraMovement(String raw) {
        return match(CAMERA_MOVEMENTS, MOVEMENT_ALIASES, raw);
    }

    /** 该值是否值得作为「自定义」保留：非空且不超过 {@link #MAX_CUSTOM_LENGTH}。 */
    public static boolean keepCustom(String raw) {
        if (raw == null) return false;
        String trimmed = raw.trim();
        return !trimmed.isEmpty() && trimmed.length() <= MAX_CUSTOM_LENGTH;
    }

    /** 默认否定词表拼成逗号串。 */
    public static String defaultNegativePrompt() {
        return String.join(",", DEFAULT_NEGATIVE_PROMPTS);
    }

    /**
     * 提示词里给模型的取值约束（system prompt 用）：
     * 明确列出可选值，并要求不确定时选默认值，而不是自造新词。
     */
    public static String promptConstraint() {
        return "景别只能取：" + String.join(" / ", SHOT_TYPES)
                + "。运镜只能取：" + String.join(" / ", CAMERA_MOVEMENTS)
                + "。除非剧情确实需要字典外的特殊运镜，否则必须选标准词；"
                + "需要特殊运镜时最多 " + MAX_CUSTOM_LENGTH + " 个字，且不得自造景别。";
    }

    private static String match(List<String> canonical, Map<String, String> aliases, String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;
        if (canonical.contains(value)) return value;
        String lower = value.toLowerCase(Locale.ROOT);
        String best = null;
        int bestLength = -1;
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            String alias = entry.getKey();
            if (!lower.contains(alias)) continue;
            if (alias.length() > bestLength) {
                bestLength = alias.length();
                best = entry.getValue();
            }
        }
        return best;
    }

    /** 别名表按 key 长度降序排好，使 contains 匹配天然优先长词。 */
    private static Map<String, String> aliases(Map<String, String> source) {
        Map<String, String> ordered = new LinkedHashMap<>();
        source.entrySet().stream()
                .sorted((left, right) -> Integer.compare(right.getKey().length(), left.getKey().length()))
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        // 必须用 unmodifiableMap（保序）；Map.copyOf 会丢迭代顺序，长词优先就失效了
        return java.util.Collections.unmodifiableMap(ordered);
    }
}
