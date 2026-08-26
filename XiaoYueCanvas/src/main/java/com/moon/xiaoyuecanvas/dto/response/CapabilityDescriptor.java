package com.moon.xiaoyuecanvas.dto.response;

/**
 * Morrow 远程能力描述（契约 {@code GET /capabilities}）。
 */
public record CapabilityDescriptor(String id, String label, String category, boolean available) {
}
