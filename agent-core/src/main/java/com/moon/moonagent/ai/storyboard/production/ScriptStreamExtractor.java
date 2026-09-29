package com.moon.moonagent.ai.storyboard.production;

/**
 * 从流式 JSON 输出里增量抽出指定字符串字段的已解码文本（如 optimizedScript）。
 * 模型这一步的原始输出是 JSON，直接展示会满屏转义符，所以只把脚本文本推给前端。
 */
final class ScriptStreamExtractor {

    private final String marker;
    private final StringBuilder raw = new StringBuilder();
    private boolean started;
    private boolean finished;
    private boolean escaped;
    private int index;

    ScriptStreamExtractor(String field) {
        this.marker = "\"" + (field == null ? "" : field) + "\"";
    }

    /** 追加一个流式分片，返回本次新解出的文本（没有新内容时返回空串）。 */
    String accept(String chunk) {
        if (finished || chunk == null || chunk.isEmpty()) return "";
        raw.append(chunk);
        if (!started && !locateValueStart()) return "";
        StringBuilder out = new StringBuilder();
        while (index < raw.length()) {
            char current = raw.charAt(index);
            if (escaped) {
                escaped = false;
                index++;
                out.append(switch (current) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    default -> current;
                });
                continue;
            }
            if (current == '\\') {
                escaped = true;
                index++;
                continue;
            }
            if (current == '"') {
                finished = true;
                index++;
                break;
            }
            out.append(current);
            index++;
        }
        return out.toString();
    }

    private boolean locateValueStart() {
        int found = raw.indexOf(marker);
        if (found < 0) return false;
        int colon = raw.indexOf(":", found + marker.length());
        if (colon < 0) return false;
        int quote = colon + 1;
        while (quote < raw.length() && Character.isWhitespace(raw.charAt(quote))) quote++;
        if (quote >= raw.length()) return false;
        if (raw.charAt(quote) != '"') return false;
        index = quote + 1;
        started = true;
        return true;
    }
}
