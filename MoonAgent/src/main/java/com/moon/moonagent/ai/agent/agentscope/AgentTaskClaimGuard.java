package com.moon.moonagent.ai.agent.agentscope;

import java.util.regex.Pattern;

/**
 * Delays only the current sentence until the runtime can verify whether a
 * claimed asynchronous task actually exists.
 */
final class AgentTaskClaimGuard {

    private static final Pattern CLAIM = Pattern.compile(
            "已提交|已受理|已下达|已创建任务|任务已创建|正在生成|后台处理中|已开始生成");

    private final StringBuilder held = new StringBuilder();
    private final StringBuilder pending = new StringBuilder();

    synchronized String accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) return "";
        pending.append(chunk);
        StringBuilder visible = new StringBuilder();
        int boundary;
        while ((boundary = sentenceBoundary(pending)) >= 0) {
            String sentence = pending.substring(0, boundary + 1);
            pending.delete(0, boundary + 1);
            if (CLAIM.matcher(sentence).find()) held.append(sentence);
            else visible.append(sentence);
        }
        return visible.toString();
    }

    synchronized boolean hasClaim() {
        return CLAIM.matcher(held).find() || CLAIM.matcher(pending).find();
    }

    synchronized String flushAll() {
        String visible = held + pending.toString();
        clear();
        return visible;
    }

    synchronized String reject() {
        clear();
        return "任务未创建，请重试。";
    }

    private void clear() {
        held.setLength(0);
        pending.setLength(0);
    }

    private static int sentenceBoundary(StringBuilder value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '。' || current == '！' || current == '？'
                    || current == '!' || current == '?' || current == ';'
                    || current == '；' || current == '\n') {
                return index;
            }
        }
        return -1;
    }
}
