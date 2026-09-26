package com.moon.moonagent.ai.agent.storyboard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 视频生成时长门禁：单次直出视频最长 15 秒，更长的内容必须先完成分镜。
 */
public final class VideoGenerationPolicy {

    public static final int MAX_DIRECT_SECONDS = 15;

    private static final String STORYBOARD_REQUIRED_MESSAGE =
            "单次视频最长 15 秒；总时长超过 15 秒时必须先走分镜流程生成分镜列表，再按镜头生成视频。";
    private static final Pattern BARE_SECONDS = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*$");

    private VideoGenerationPolicy() {
    }

    public static boolean requiresStoryboard(String duration, String originalRequest) {
        Integer explicit = parseSeconds(duration);
        if (explicit != null && explicit > MAX_DIRECT_SECONDS) {
            return true;
        }
        Integer requestedTotal = SceneDurationPlanner.parseTotalSeconds(originalRequest);
        return requestedTotal != null && requestedTotal > MAX_DIRECT_SECONDS;
    }

    public static void requireDirectAllowed(String duration, String originalRequest) {
        if (requiresStoryboard(duration, originalRequest)) {
            throw new IllegalStateException(STORYBOARD_REQUIRED_MESSAGE);
        }
    }

    public static void requireStoryboardShotAllowed(String duration) {
        Integer shotDuration = parseSeconds(duration);
        if (shotDuration != null && shotDuration > MAX_DIRECT_SECONDS) {
            throw new IllegalStateException(STORYBOARD_REQUIRED_MESSAGE);
        }
    }

    static Integer parseSeconds(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Integer parsed = SceneDurationPlanner.parseTotalSeconds(value);
        if (parsed != null) {
            return parsed;
        }
        Matcher matcher = BARE_SECONDS.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(matcher.group(1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
