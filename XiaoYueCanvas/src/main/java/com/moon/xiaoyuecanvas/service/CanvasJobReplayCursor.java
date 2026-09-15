package com.moon.xiaoyuecanvas.service;

/** Resolves the durable cursor used by {@code GET /jobs/{jobId}/events}. */
public final class CanvasJobReplayCursor {

    private CanvasJobReplayCursor() {}

    public static long resolve(Long afterSeq, String lastEventId) {
        if (afterSeq != null) return Math.max(0L, afterSeq);
        if (lastEventId == null || lastEventId.isBlank()) return 0L;

        String value = lastEventId.trim();
        int separator = value.lastIndexOf(':');
        if (separator >= 0) value = value.substring(separator + 1);
        try {
            return Math.max(0L, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
