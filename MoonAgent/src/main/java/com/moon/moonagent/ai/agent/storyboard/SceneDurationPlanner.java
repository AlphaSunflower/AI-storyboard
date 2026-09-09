package com.moon.moonagent.ai.agent.storyboard;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将用户明确给出的目标总时长拆分为逐镜整数时长。
 *
 * <p>视频模型单镜时长约束为 4~15 秒。只有目标总时长在镜头数可承载范围内才返回逐镜结果；
 * 不可行时返回明确原因，调用方保留推荐参数并提示用户，避免静默改写总时长。
 */
public final class SceneDurationPlanner {

    public static final int MIN_SCENE_DURATION = 4;
    public static final int MAX_SCENE_DURATION = 15;

    private static final Pattern MINUTE_SECOND = Pattern.compile(
            "(\\d+(?:\\.\\d+)?|[零〇一二两三四五六七八九十百]+)\\s*(?:分钟|分(?!镜))"
                    + "\\s*(\\d+)\\s*秒");
    private static final Pattern MINUTE = Pattern.compile(
            "(\\d+(?:\\.\\d+)?|[零〇一二两三四五六七八九十百]+)\\s*(?:分钟|分(?!镜)|min(?:ute)?s?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SECOND = Pattern.compile(
            "(\\d+(?:\\.\\d+)?|[零〇一二两三四五六七八九十百]+)\\s*(?:秒|s(?:ec(?:ond)?s?)?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PER_SHOT = Pattern.compile("每\\s*(?:镜|个镜头|段)|单\\s*镜|各\\s*镜");

    private SceneDurationPlanner() {}

    /** 解析明确的目标总秒数；未命中、每镜时长描述或非法值返回 null。 */
    public static Integer parseTotalSeconds(String text) {
        if (text == null || text.isBlank()) return null;
        Integer minuteSecond = lastMatchSeconds(MINUTE_SECOND, text, true);
        if (minuteSecond != null) return minuteSecond;
        Integer minute = lastMatchSeconds(MINUTE, text, false);
        if (minute != null) return minute;
        return lastMatchSeconds(SECOND, text, false);
    }

    /** 按镜头正文权重分配时长；不可行时返回空列表和用户可读原因。 */
    public static Allocation allocate(int totalSeconds, List<String> sceneContents) {
        int count = sceneContents == null ? 0 : sceneContents.size();
        if (count < 1) return Allocation.infeasible(totalSeconds, "没有可分镜内容，无法应用目标总时长。");
        int minTotal = count * MIN_SCENE_DURATION;
        int maxTotal = count * MAX_SCENE_DURATION;
        if (totalSeconds < minTotal || totalSeconds > maxTotal) {
            return Allocation.infeasible(totalSeconds,
                    "目标总时长 " + totalSeconds + " 秒无法拆到 " + count
                            + " 个分镜：每镜限 " + MIN_SCENE_DURATION + "~" + MAX_SCENE_DURATION
                            + " 秒，可承载范围为 " + minTotal + "~" + maxTotal + " 秒。已保留推荐时长。");
        }

        int[] weights = new int[count];
        for (int i = 0; i < count; i++) {
            String content = sceneContents.get(i);
            weights[i] = content == null ? 1 : Math.max(1, content.length());
        }
        int[] values = new int[count];
        java.util.Arrays.fill(values, MIN_SCENE_DURATION);
        int remaining = totalSeconds - minTotal;
        while (remaining > 0) {
            int chosen = -1;
            double bestScore = Double.POSITIVE_INFINITY;
            for (int i = 0; i < count; i++) {
                if (values[i] >= MAX_SCENE_DURATION) continue;
                double score = (values[i] - MIN_SCENE_DURATION + 1.0) / weights[i];
                if (score < bestScore || (score == bestScore && (chosen < 0 || weights[i] > weights[chosen]))) {
                    chosen = i;
                    bestScore = score;
                }
            }
            if (chosen < 0) break;
            values[chosen]++;
            remaining--;
        }
        if (remaining != 0) {
            return Allocation.infeasible(totalSeconds, "目标总时长分配失败，已保留推荐时长。");
        }
        List<Integer> durations = new ArrayList<>(count);
        for (int value : values) durations.add(value);
        return Allocation.feasible(durations);
    }

    private static Integer lastMatchSeconds(Pattern pattern, String text, boolean minuteAndSecond) {
        Matcher matcher = pattern.matcher(text);
        Integer result = null;
        while (matcher.find()) {
            String prefix = text.substring(Math.max(0, matcher.start() - 8), matcher.start());
            if (PER_SHOT.matcher(prefix).find()) continue;
            int first = parseNumber(matcher.group(1));
            if (first <= 0) continue;
            if (minuteAndSecond) {
                int seconds = parseNumber(matcher.group(2));
                result = first * 60 + Math.max(0, seconds);
            } else if (pattern == MINUTE) {
                result = (int) Math.round(first * 60.0);
            } else {
                result = first;
            }
        }
        return result;
    }

    private static int parseNumber(String raw) {
        if (raw == null || raw.isBlank()) return -1;
        if (raw.matches("\\d+(?:\\.\\d+)?")) {
            try {
                return (int) Math.round(Double.parseDouble(raw));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        int result = 0;
        int current = 0;
        for (char c : raw.toCharArray()) {
            switch (c) {
                case '零', '〇' -> current = 0;
                case '一' -> current = 1;
                case '二', '两' -> current = 2;
                case '三' -> current = 3;
                case '四' -> current = 4;
                case '五' -> current = 5;
                case '六' -> current = 6;
                case '七' -> current = 7;
                case '八' -> current = 8;
                case '九' -> current = 9;
                case '十' -> {
                    if (current == 0) current = 1;
                    result += current * 10;
                    current = 0;
                }
                case '百' -> {
                    if (current == 0) current = 1;
                    result += current * 100;
                    current = 0;
                }
                default -> {
                    return -1;
                }
            }
        }
        return result + current;
    }

    public record Allocation(List<Integer> durations, boolean feasible, String message) {
        public Allocation {
            durations = durations == null ? List.of() : List.copyOf(durations);
        }

        static Allocation feasible(List<Integer> durations) {
            return new Allocation(durations, true, "");
        }

        static Allocation infeasible(int totalSeconds, String message) {
            return new Allocation(List.of(), false, message);
        }
    }
}
