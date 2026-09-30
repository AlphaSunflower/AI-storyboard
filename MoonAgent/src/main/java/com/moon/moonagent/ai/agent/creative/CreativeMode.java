package com.moon.moonagent.ai.agent.creative;

import java.util.List;
import java.util.Locale;

public enum CreativeMode {
    BALANCED,
    STRICT;

    private static final List<String> STRICT_MARKERS = List.of(
            "必须", "严格", "忠于", "保留", "不能改", "不得改变", "禁止改变",
            "尺寸必须", "数量必须", "保持一致", "固定不变", "必须一致", "务必"
    );

    public static CreativeMode detect(String prompt) {
        if (prompt == null || prompt.isBlank()) return BALANCED;
        String value = prompt.toLowerCase(Locale.ROOT);
        return STRICT_MARKERS.stream().anyMatch(value::contains) ? STRICT : BALANCED;
    }

    public static CreativeMode parse(String value) {
        if (value == null || value.isBlank()) return BALANCED;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return BALANCED;
        }
    }
}
