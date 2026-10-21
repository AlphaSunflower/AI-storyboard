package com.moon.moonagent.ai.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息 metadata 的「列表版」瘦身。
 *
 * <p>实测：一条 14 条消息的会话，`/messages/page` 响应 78 KB，其中 72 KB 是 metadata；
 * 而用户**点了才会看**的内容占了 83%：
 * <ul>
 *   <li>{@code reasoning.details}（一条 10 KB 级的思考全文）——只在「思考过程」面板展开时渲染；</li>
 *   <li>{@code agentTrace}（35 条轨迹）——同上；</li>
 *   <li>{@code toolDrafts[].text / .input}——折叠态只显示截断预览，展开才看全文（本步暂不动，
 *       因为折叠卡片的小标签要靠解析原文生成）。</li>
 * </ul>
 *
 * <p>列表里把前两项换成「有这个字段」的标记（{@code detailsAvailable} / {@code traceAvailable}），
 * 客户端展开时再走 {@code /messages/{id}/details} 拉全文。
 * 另外顺手删掉三个客户端零引用的字段：{@code referenceAssets} / {@code agentRuntime} /
 * {@code checkpointOwner}，以及没人读的 {@code reasoning.summary}。
 */
public final class AgentMessageMetadataSlimmer {

    /** 客户端 0 处引用的字段：列表里直接不出现。 */
    private static final List<String> UNUSED_FIELDS = List.of(
            "referenceAssets", "agentRuntime", "checkpointOwner");

    private AgentMessageMetadataSlimmer() {
    }

    /** 列表版 metadata：去掉无用字段与大块明细，只留标记。 */
    public static Map<String, Object> slim(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return metadata;
        Map<String, Object> slim = new LinkedHashMap<>(metadata);
        UNUSED_FIELDS.forEach(slim::remove);

        Object reasoning = slim.get("reasoning");
        if (reasoning instanceof Map<?, ?> raw) {
            Map<String, Object> copy = copy(raw);
            copy.remove("summary"); // 面板只读 details / durationMs，summary 没人用
            if (copy.remove("details") != null) {
                copy.put("detailsAvailable", true);
            }
            slim.put("reasoning", copy);
        }

        if (slim.remove("agentTrace") != null) {
            slim.put("traceAvailable", true);
        }
        return slim;
    }

    /** 详情版：只回大块明细，客户端展开时合并回内存。 */
    public static Map<String, Object> details(Map<String, Object> metadata) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (metadata == null) return out;
        Object reasoning = metadata.get("reasoning");
        if (reasoning instanceof Map<?, ?> raw) {
            Object details = raw.get("details");
            if (details != null) out.put("reasoningDetails", details);
        }
        Object trace = metadata.get("agentTrace");
        if (trace != null) out.put("agentTrace", trace);
        return out;
    }

    private static Map<String, Object> copy(Map<?, ?> raw) {
        Map<String, Object> copy = new LinkedHashMap<>();
        raw.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }
}
