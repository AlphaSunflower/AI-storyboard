package com.storyboard.asset.domain;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;

/** 生成用于 pg_trgm 检索的稳定全文文本。 */
public final class AssetSearchText {

    private AssetSearchText() {
    }

    public static String build(String name, String description, String aiDescription, Collection<String> tags) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        add(parts, name);
        add(parts, description);
        add(parts, aiDescription);
        if (tags != null) tags.forEach(tag -> add(parts, tag));
        return String.join(" ", parts).toLowerCase(Locale.ROOT);
    }

    private static void add(LinkedHashSet<String> parts, String value) {
        if (value == null || value.isBlank()) return;
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (!normalized.isBlank()) parts.add(normalized);
    }
}
