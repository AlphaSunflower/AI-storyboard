package com.moon.moonagent.ai.agent;

/**
 * Plan JSON 字段名枚举 — 消除 planField(planJson, "content") 魔法字符串。
 *
 * <p>用法：{@code support.planField(cp.getPlan(), PlanField.CONTENT)}
 */
public enum PlanField {
    CONTENT("content"),
    SOURCE("source"),
    PIC_URL("picUrl"),
    ASSET_IDS("assetIds"),
    KIND("kind"),
    ORIGINAL_CONTENT("originalContent"),
    DURATION("duration"),
    PROMPT("prompt"),
    MESSAGE("message");

    private final String key;

    PlanField(String key) { this.key = key; }

    public String key() { return key; }
}
