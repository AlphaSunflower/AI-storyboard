package com.moon.moonagent.ai.agent.human;

import java.util.Locale;

/**
 * 审批结果的闭合词表。
 *
 * <p>只允许四种终态：{@link #ALLOWED}、{@link #REJECTED}、{@link #CANCELLED}、
 * {@link #UNAVAILABLE}。任何无法识别的动作一律归为 {@link #UNAVAILABLE}，
 * 并且 {@link #allowsExecution()} 为 {@code false}——审批通道缺失、动作非法或
 * 调用方漏传时必须 fail-closed，不能默认放行。
 */
public enum ApprovalOutcome {

    /** 用户明确允许本次操作。 */
    ALLOWED("allowed"),

    /** 用户明确拒绝；本轮终止，不得把拒绝结果再喂给模型。 */
    REJECTED("rejected"),

    /** 用户在等待审批期间中断/取消。 */
    CANCELLED("cancelled"),

    /** 审批通道不可用、动作非法或无法判定；按拒绝处理。 */
    UNAVAILABLE("unavailable");

    private final String wireValue;

    ApprovalOutcome(String wireValue) {
        this.wireValue = wireValue;
    }

    /** 模型与审计看到的稳定小写值。 */
    public String wireValue() {
        return wireValue;
    }

    /** 是否允许真正执行工具。仅 {@link #ALLOWED} 为真。 */
    public boolean allowsExecution() {
        return this == ALLOWED;
    }

    /**
     * 把前端提交的 action 收敛到闭合词表。
     *
     * @param action 前端动作；为空或无法识别时返回 {@link #UNAVAILABLE}
     * @return 闭合词表中的审批结果
     */
    public static ApprovalOutcome fromAction(String action) {
        if (action == null || action.isBlank()) {
            return UNAVAILABLE;
        }
        return switch (action.trim().toLowerCase(Locale.ROOT)) {
            case "approve", "approved", "allow", "allowed", "agree", "yes", "continue" ->
                    ALLOWED;
            case "reject", "rejected", "deny", "no" -> REJECTED;
            case "cancel", "cancelled", "canceled", "stop", "abort" -> CANCELLED;
            default -> UNAVAILABLE;
        };
    }
}
