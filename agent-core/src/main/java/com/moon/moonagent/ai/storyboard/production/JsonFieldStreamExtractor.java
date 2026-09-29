package com.moon.moonagent.ai.storyboard.production;

/**
 * Extracts clean, decoded string values from a streamed JSON object or array.
 * Repeated fields (for example changes[].replacement) are emitted as they appear.
 */
final class JsonFieldStreamExtractor {

    private final String marker;
    private final String label;
    private final StringBuilder raw = new StringBuilder();

    private int searchFrom;
    private int valueStart = -1;
    private boolean escaped;
    private boolean emittedLabel;

    JsonFieldStreamExtractor(String field, String label) {
        this.marker = "\"" + (field == null ? "" : field) + "\"";
        this.label = label == null ? "" : label;
    }

    String accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) return "";
        raw.append(chunk);
        StringBuilder output = new StringBuilder();
        while (true) {
            if (valueStart < 0 && !locateValueStart()) break;
            boolean completedValue = false;
            while (valueStart < raw.length()) {
                char current = raw.charAt(valueStart);
                if (escaped) {
                    escaped = false;
                    valueStart++;
                    append(output, switch (current) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        default -> current;
                    });
                    continue;
                }
                if (current == '\\') {
                    escaped = true;
                    valueStart++;
                    continue;
                }
                if (current == '"') {
                    valueStart++;
                    completedValue = true;
                    break;
                }
                append(output, current);
                valueStart++;
            }
            if (!completedValue) break;
            searchFrom = valueStart;
            valueStart = -1;
        }
        return output.toString();
    }

    private boolean locateValueStart() {
        int found = raw.indexOf(marker, searchFrom);
        if (found < 0) return false;
        int colon = raw.indexOf(":", found + marker.length());
        if (colon < 0) return false;
        int quote = colon + 1;
        while (quote < raw.length() && Character.isWhitespace(raw.charAt(quote))) quote++;
        if (quote >= raw.length()) return false;
        if (raw.charAt(quote) != '"') {
            searchFrom = found + marker.length();
            return valueStart >= 0;
        }
        valueStart = quote + 1;
        searchFrom = valueStart;
        return true;
    }

    private void append(StringBuilder output, char value) {
        if (!emittedLabel && !label.isBlank()) {
            output.append(label).append('\n');
            emittedLabel = true;
        }
        output.append(value);
    }
}
