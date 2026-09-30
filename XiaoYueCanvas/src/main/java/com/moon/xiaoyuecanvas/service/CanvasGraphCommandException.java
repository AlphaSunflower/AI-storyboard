package com.moon.xiaoyuecanvas.service;

import java.util.Map;

/** Stable, model-readable error codes for server-side canvas graph commands. */
public class CanvasGraphCommandException extends RuntimeException {

    private final String code;
    private final Map<String, Object> details;

    public CanvasGraphCommandException(String code, String message) {
        this(code, message, Map.of());
    }

    public CanvasGraphCommandException(String code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
