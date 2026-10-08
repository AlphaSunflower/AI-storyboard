package com.storyboard.ai.impl;

final class StoryboardResponseParser {

    private StoryboardResponseParser() {
    }

    static String extractJsonArray(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("AI 返回内容为空");
        }
        String trimmed = stripCodeFence(content.trim());
        int arrayStart = findArrayStart(trimmed);
        if (arrayStart < 0) {
            throw new IllegalArgumentException("AI 返回内容中未找到 JSON 数组");
        }
        int arrayEnd = findBalancedEnd(trimmed, arrayStart, '[', ']');
        if (arrayEnd > arrayStart) {
            return trimmed.substring(arrayStart, arrayEnd + 1);
        }
        return salvageCompleteObjects(trimmed, arrayStart);
    }

    private static String stripCodeFence(String content) {
        if (!content.startsWith("```")) return content;
        int firstLine = content.indexOf('\n');
        int lastFence = content.lastIndexOf("```");
        if (firstLine >= 0 && lastFence > firstLine) {
            return content.substring(firstLine + 1, lastFence).trim();
        }
        return content;
    }

    private static int findArrayStart(String content) {
        int direct = content.indexOf('[');
        if (direct >= 0) return direct;
        int objectStart = content.indexOf('{');
        if (objectStart < 0) return -1;
        int nested = content.indexOf('[', objectStart);
        return nested >= 0 ? nested : -1;
    }

    private static int findBalancedEnd(String content, int start, char open, char close) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == open) {
                depth++;
            } else if (ch == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static String salvageCompleteObjects(String content, int arrayStart) {
        int cursor = arrayStart + 1;
        StringBuilder out = new StringBuilder("[");
        boolean first = true;
        while (cursor < content.length()) {
            int objectStart = content.indexOf('{', cursor);
            if (objectStart < 0) break;
            int objectEnd = findBalancedEnd(content, objectStart, '{', '}');
            if (objectEnd < 0) break;
            if (!first) out.append(',');
            out.append(content, objectStart, objectEnd + 1);
            first = false;
            cursor = objectEnd + 1;
        }
        if (first) {
            throw new IllegalArgumentException("AI 返回的 JSON 数组不完整");
        }
        return out.append(']').toString();
    }
}
