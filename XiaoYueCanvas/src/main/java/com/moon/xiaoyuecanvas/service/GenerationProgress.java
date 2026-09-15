package com.moon.xiaoyuecanvas.service;

/** 生成进度归一化：优先使用上游真实进度，无进度时提供单调递增的阶段进度。 */
public final class GenerationProgress {

    private static final int BUSY_CEILING = 95;

    private GenerationProgress() {
    }

    public static int fromProvider(String raw, int previous) {
        Integer parsed = parse(raw);
        if (parsed == null) return tick(previous, BUSY_CEILING, 5);
        return Math.min(BUSY_CEILING, Math.max(Math.max(5, previous), parsed));
    }

    public static int tick(int previous, int max, int step) {
        return Math.min(Math.max(0, max), Math.max(0, previous) + Math.max(1, step));
    }

    private static Integer parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        if (value.endsWith("%")) value = value.substring(0, value.length() - 1).trim();
        try {
            double parsed = Double.parseDouble(value);
            if (parsed < 0) return null;
            if (parsed > 0 && parsed <= 1) parsed *= 100;
            return (int) Math.round(Math.min(100, parsed));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
