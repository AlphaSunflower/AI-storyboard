package com.moon.moonagent.ai.agent.specialist;

import java.util.Map;

/**
 * 专家 Agent 执行结果 + 移交载荷。
 *
 * @param message        最后一条消息内容（供落库 assistant 消息）
 * @param handoffTarget  null=完成；"intent-xxx"=移交给另一个专家
 * @param handoffReason  移交原因
 * @param handoffState   传递给下一个专家的状态
 */
public record SpecialistResult(
        String message,
        String handoffTarget,
        String handoffReason,
        Map<String, Object> handoffState
) {
    /** 完成（无需移交） */
    public static SpecialistResult done(String message) {
        return new SpecialistResult(message, null, null, null);
    }

    /** 移交给另一个专家 */
    public static SpecialistResult handoff(String target, String reason, Map<String, Object> state) {
        return new SpecialistResult(null, target, reason, state);
    }

    public boolean needsHandoff() {
        return handoffTarget != null && !handoffTarget.isBlank();
    }
}
