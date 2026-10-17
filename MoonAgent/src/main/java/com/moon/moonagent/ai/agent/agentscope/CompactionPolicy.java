package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.AgentContextWindowResolver;

/**
 * 上下文压缩阈值策略。
 *
 * <p>抽成纯静态函数（无 Spring 依赖）以便直接单测：输入「模型窗口 / 比例 / 显式兜底值 /
 * 下限 / 消息数阈值」→ 输出最终交给 AgentScope harness {@code CompactionConfig} 的两个阈值。
 *
 * <p>语义：
 * <ul>
 *   <li>{@code ratio > 0}：{@code triggerTokens = max(floor, round(窗口 × ratio))}；
 *       窗口解析失败（{@code <= 0}）时用 {@link AgentContextWindowResolver#DEFAULT_WINDOW_TOKENS}。</li>
 *   <li>{@code ratio <= 0}：退回显式 {@code explicitTriggerTokens}（人工兜底）。</li>
 *   <li>消息数条件原样透出，{@code <= 0} 即"禁用"——harness 的
 *       {@code ConversationCompactor.shouldCompact} 只在 {@code > 0} 时才比较消息数。</li>
 * </ul>
 */
public final class CompactionPolicy {

    private CompactionPolicy() {
    }

    /**
     * 计算最终压缩阈值。
     *
     * @param modelWindowTokens    解析到的模型上下文窗口；{@code <= 0} 表示解析失败
     * @param ratio                自动比例；{@code > 0} 时启用窗口比例口径
     * @param explicitTriggerTokens 显式兜底 token 阈值（仅 {@code ratio <= 0} 时生效）
     * @param floorTokens          自动阈值的下限
     * @param triggerMessages      消息数阈值；{@code <= 0} 表示禁用该条件
     * @return 最终阈值
     */
    public static Thresholds resolve(int modelWindowTokens,
                                     double ratio,
                                     int explicitTriggerTokens,
                                     int floorTokens,
                                     int triggerMessages) {
        int window = modelWindowTokens > 0
                ? modelWindowTokens
                : AgentContextWindowResolver.DEFAULT_WINDOW_TOKENS;
        int triggerTokens;
        if (ratio > 0d) {
            long scaled = Math.round(window * ratio);
            long bounded = Math.max(Math.max(1L, floorTokens), scaled);
            triggerTokens = (int) Math.min(Integer.MAX_VALUE, bounded);
        } else {
            triggerTokens = Math.max(1, explicitTriggerTokens);
        }
        return new Thresholds(triggerTokens, Math.max(0, triggerMessages));
    }

    /**
     * 最终阈值。
     *
     * @param triggerTokens   压缩触发的 token 阈值（恒 {@code > 0}）
     * @param triggerMessages 压缩触发的消息数阈值；{@code <= 0} 表示该条件禁用
     */
    public record Thresholds(int triggerTokens, int triggerMessages) {

        /** 消息数条件是否被禁用。 */
        public boolean messagesConditionDisabled() {
            return triggerMessages <= 0;
        }
    }
}
