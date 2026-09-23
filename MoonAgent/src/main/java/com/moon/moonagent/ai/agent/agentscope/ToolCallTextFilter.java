package com.moon.moonagent.ai.agent.agentscope;

/**
 * Some OpenAI-compatible models occasionally put an XML-style tool call in text instead of
 * returning structured tool_calls. Never expose that transport artifact to the user.
 */
final class ToolCallTextFilter {

    private static final String START = "<tool_call>";
    private static final String END = "</tool_call>";
    private static final int LOOKBEHIND = Math.max(START.length(), END.length()) - 1;

    private final StringBuilder pending = new StringBuilder();
    private boolean suppressing;

    synchronized String accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) return "";
        pending.append(chunk);
        StringBuilder out = new StringBuilder();
        while (true) {
            if (suppressing) {
                int end = pending.indexOf(END);
                if (end < 0) {
                    if (pending.length() > LOOKBEHIND) {
                        pending.delete(0, pending.length() - LOOKBEHIND);
                    }
                    break;
                }
                pending.delete(0, end + END.length());
                suppressing = false;
                continue;
            }
            int start = pending.indexOf(START);
            if (start < 0) {
                int possibleTag = pending.lastIndexOf("<");
                if (possibleTag >= 0) {
                    String tail = pending.substring(possibleTag);
                    if (START.startsWith(tail)) {
                        out.append(pending, 0, possibleTag);
                        pending.delete(0, possibleTag);
                        break;
                    }
                }
                out.append(pending);
                pending.setLength(0);
                break;
            }
            out.append(pending, 0, start);
            pending.delete(0, start + START.length());
            suppressing = true;
        }
        return out.toString();
    }

    synchronized String finish() {
        if (suppressing) {
            pending.setLength(0);
            return "";
        }
        String out = pending.toString();
        pending.setLength(0);
        return out;
    }

    static String sanitizeComplete(String text) {
        if (text == null || text.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        while (cursor < text.length()) {
            int start = text.indexOf(START, cursor);
            if (start < 0) {
                out.append(text, cursor, text.length());
                break;
            }
            out.append(text, cursor, start);
            int end = text.indexOf(END, start + START.length());
            if (end < 0) break;
            cursor = end + END.length();
        }
        return out.toString().trim();
    }
}
