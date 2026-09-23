package com.moon.moonagent.orchestration;

import java.util.Locale;

public enum ChainType {
    STORYBOARD,
    AIGC,
    CANVAS;

    public static ChainType from(String value) {
        if (value == null || value.isBlank()) return STORYBOARD;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return STORYBOARD;
        }
    }
}
