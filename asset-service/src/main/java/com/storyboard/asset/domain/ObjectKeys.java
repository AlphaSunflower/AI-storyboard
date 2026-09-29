package com.storyboard.asset.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 历史 URL / JSON 数组到 object key 的保守解析；无法确定时返回 null 或空集合。 */
public final class ObjectKeys {

    private static final String API_FILES_MARKER = "/api/files/";
    private static final List<String> PREFIXES = List.of("images/", "videos/", "audios/", "files/");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ObjectKeys() {
    }

    public static String fromUrl(String url) {
        if (url == null || url.isBlank()) return null;
        String clean = url.trim();
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        int fragment = clean.indexOf('#');
        if (fragment >= 0) clean = clean.substring(0, fragment);
        int marker = clean.indexOf(API_FILES_MARKER);
        if (marker >= 0) clean = clean.substring(marker + API_FILES_MARKER.length());
        for (String prefix : PREFIXES) {
            if (clean.startsWith(prefix) && clean.length() > prefix.length()) return clean;
        }
        return null;
    }

    public static Set<String> fromJsonArray(String json) {
        if (json == null || json.isBlank()) return Set.of();
        Set<String> keys = new LinkedHashSet<>();
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!node.isArray()) return Set.of();
            for (JsonNode element : node) {
                if (!element.isTextual()) continue;
                String key = fromUrl(element.asText());
                if (key != null) keys.add(key);
            }
        } catch (Exception e) {
            return Set.of();
        }
        return keys;
    }
}