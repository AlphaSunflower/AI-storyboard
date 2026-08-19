package com.moon.moonagent.ai.agent;

/**
 * 意图识别结果：type + 置信度 + 来源。
 *
 * <p>source 取值：
 * <ul>
 *   <li>{@code rule} — 规则前置匹配命中（强关键词直接路由，免一次 LLM 调用，置信度 1.0）</li>
 *   <li>{@code llm} — 模型识别（JSON 输出 {@code {type, confidence}}；缺失 confidence 视为 0.5 走阈值判断）</li>
 *   <li>{@code fallback} — 识别失败/白名单外兜底（intent-other，置信度 0）</li>
 * </ul>
 */
public record IntentResult(String type, double confidence, String source) {

    /** 规则命中置信度（免 LLM，直接路由） */
    public static final double RULE_CONFIDENCE = 1.0;

    /** 兜底置信度（识别失败 → intent-other，不阻塞对话） */
    public static final double FALLBACK_CONFIDENCE = 0.0;

    /** 规则前置命中 */
    public static IntentResult rule(String type) {
        return new IntentResult(type, RULE_CONFIDENCE, "rule");
    }

    /** LLM 识别（confidence 缺失/非法时按 0.5 视为不明确，交由阈值判断） */
    public static IntentResult llm(String type, double confidence) {
        double c = Double.isNaN(confidence) ? 0.5 : Math.max(0.0, Math.min(1.0, confidence));
        return new IntentResult(type, c, "llm");
    }

    /** 兜底（识别失败/白名单外） */
    public static IntentResult fallback() {
        return new IntentResult(IntentRecognitionService.FALLBACK_TYPE, FALLBACK_CONFIDENCE, "fallback");
    }
}
