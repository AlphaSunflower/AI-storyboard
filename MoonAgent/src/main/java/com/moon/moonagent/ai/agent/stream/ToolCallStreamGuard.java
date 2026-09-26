package com.moon.moonagent.ai.agent.stream;

import java.util.HashMap;
import java.util.Map;

/**
 * 文本形态工具调用的流式守卫。
 *
 * <p>部分 OpenAI 兼容模型不返回结构化 {@code tool_calls}，而是把调用写成
 * {@code <tool_call><function=name>...</function></tool_call>} 文本。这种退化有两个后果：
 * 传输标记会漏进用户可见的回复；模型有时会陷入重复输出同一段调用、既不执行也不停止的
 * 死循环。本守卫负责剥掉标记，并在检测到重复退化时抛异常中止本轮。
 *
 * <p>刻意不限制总字数——正常长文回复不应被截断；只在出现工具调用文本块时计数。
 */
public final class ToolCallStreamGuard {

    private static final String START = "<tool_call>";
    private static final String END = "</tool_call>";
    private static final int LOOKBEHIND = Math.max(START.length(), END.length()) - 1;

    /** 同一段工具调用重复出现超过该次数即判定为退化循环（模型在自问自答式重试）。 */
    static final int MAX_IDENTICAL_CALLS = 8;
    /** 单轮工具调用块的绝对上限：内容各不相同但数量失控时兜底。 */
    static final int MAX_TOTAL_CALLS = 60;

    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder block = new StringBuilder();
    private final Map<String, Integer> callCounts = new HashMap<>();
    private boolean suppressing;
    private int totalCalls;

    /** 送入一段流式增量，返回可直接展示给用户的文本；检测到退化循环时抛异常。 */
    public synchronized String accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) return "";
        pending.append(chunk);
        StringBuilder out = new StringBuilder();
        while (true) {
            if (suppressing) {
                int end = pending.indexOf(END);
                if (end < 0) {
                    // 只把确定不属于 END 前缀的部分并入块内容，尾部留给跨 chunk 的标签检测。
                    int keep = Math.min(LOOKBEHIND, pending.length());
                    block.append(pending, 0, pending.length() - keep);
                    pending.delete(0, pending.length() - keep);
                    break;
                }
                block.append(pending, 0, end);
                pending.delete(0, end + END.length());
                suppressing = false;
                recordCall(block.toString());
                block.setLength(0);
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

    /** 收尾：未闭合的标记一律丢弃，避免半截工具调用文本漏给用户。 */
    public synchronized String finish() {
        if (suppressing) {
            pending.setLength(0);
            block.setLength(0);
            return "";
        }
        String out = pending.toString();
        pending.setLength(0);
        return out;
    }

    /** 已完成文本块的统计（测试与埋点用）。 */
    public synchronized int totalToolCalls() {
        return totalCalls;
    }

    /** 一次性清洗完整文本里的工具调用标记（非流式路径使用）。 */
    public static String sanitizeComplete(String text) {
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

    private void recordCall(String raw) {
        totalCalls++;
        String normalized = raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
        int identical = callCounts.merge(normalized, 1, Integer::sum);
        if (identical >= MAX_IDENTICAL_CALLS) {
            throw new ToolCallLoopException("模型重复输出了同一段工具调用 " + identical + " 次，已中止本轮生成");
        }
        if (totalCalls >= MAX_TOTAL_CALLS) {
            throw new ToolCallLoopException("模型连续输出了 " + totalCalls + " 段工具调用文本，已中止本轮生成");
        }
    }

    /** 模型退化为重复文本工具调用时抛出，调用方应中止本轮并给用户友好提示。 */
    public static final class ToolCallLoopException extends RuntimeException {
        public ToolCallLoopException(String message) {
            super(message);
        }
    }
}
