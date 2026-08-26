package com.moon.xiaoyuecanvas.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 轻量 JSON 工具（对象↔TEXT 列，供 canvas_jobs 的参数/节点/素材 JSON 字段使用）。
 */
public final class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonUtil() {}

    public static String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    public static Map<String, Object> parse(String s) {
        if (s == null || s.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(s, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    public static List<String> parseList(String s) {
        if (s == null || s.isBlank()) return List.of();
        try {
            return MAPPER.readValue(s, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
