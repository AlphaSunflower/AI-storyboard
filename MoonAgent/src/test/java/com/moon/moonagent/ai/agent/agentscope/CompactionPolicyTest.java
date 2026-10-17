package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.AgentContextWindowResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 压缩阈值策略：自动口径 = max(floor, 窗口 × ratio)，ratio<=0 退回显式值，
 * 消息数条件 <=0 表示禁用。
 */
class CompactionPolicyTest {

    @Test
    void usesConfiguredRatioOfModelWindow() {
        // 1M 窗口 × 0.5 = 500k：不再用固定 48k
        CompactionPolicy.Thresholds thresholds =
                CompactionPolicy.resolve(1_000_000, 0.5, 48_000, 60_000, 400);

        assertThat(thresholds.triggerTokens()).isEqualTo(500_000);
        assertThat(thresholds.triggerMessages()).isEqualTo(400);
        assertThat(thresholds.messagesConditionDisabled()).isFalse();
    }

    @Test
    void ratioResultAboveFloorWins() {
        // 128k × 0.5 = 64000 > floor 60000 → 取比例值
        assertThat(CompactionPolicy.resolve(128_000, 0.5, 48_000, 60_000, 400).triggerTokens())
                .isEqualTo(64_000);
    }

    @Test
    void floorAppliesWhenRatioResultIsLower() {
        // 100k × 0.5 = 50000 < floor 60000 → floor 生效
        assertThat(CompactionPolicy.resolve(100_000, 0.5, 48_000, 60_000, 400).triggerTokens())
                .isEqualTo(60_000);
    }

    @Test
    void fallsBackToDefaultWindowWhenResolutionFails() {
        int expected = (int) Math.round(AgentContextWindowResolver.DEFAULT_WINDOW_TOKENS * 0.5);

        assertThat(CompactionPolicy.resolve(0, 0.5, 48_000, 1, 400).triggerTokens())
                .isEqualTo(expected);
        assertThat(CompactionPolicy.resolve(-1, 0.5, 48_000, 1, 400).triggerTokens())
                .isEqualTo(expected);
    }

    @Test
    void explicitValueUsedWhenRatioDisabled() {
        assertThat(CompactionPolicy.resolve(1_000_000, 0d, 48_000, 60_000, 400))
                .isEqualTo(new CompactionPolicy.Thresholds(48_000, 400));
        assertThat(CompactionPolicy.resolve(1_000_000, -1d, 48_000, 60_000, 400).triggerTokens())
                .isEqualTo(48_000);
    }

    @Test
    void messageCountConditionCanBeDisabled() {
        assertThat(CompactionPolicy.resolve(1_000_000, 0.5, 48_000, 60_000, 0)
                .messagesConditionDisabled()).isTrue();
        assertThat(CompactionPolicy.resolve(1_000_000, 0.5, 48_000, 60_000, -5)
                .messagesConditionDisabled()).isTrue();
        assertThat(CompactionPolicy.resolve(1_000_000, 0.5, 48_000, 60_000, -5)
                .triggerMessages()).isZero();
    }

    @Test
    void tokenThresholdIsAlwaysPositive() {
        // floor 与显式值都被非法配置成 <=0 时，仍必须给出正阈值（0 会被 harness 当成"禁用"）
        assertThat(CompactionPolicy.resolve(1_000_000, 0.5, 0, 0, 400).triggerTokens())
                .isPositive();
        assertThat(CompactionPolicy.resolve(1_000_000, 0d, 0, 0, 400).triggerTokens())
                .isPositive();
    }

    @Test
    void propertiesZeroDisablesMessageConditionInsteadOfClampingToOne() {
        // 回归：旧 setter 的 Math.max(1, x) 会把 0 钳成 1 → 每条消息都触发压缩
        AgentScopeProperties properties = new AgentScopeProperties();

        properties.setCompactionTriggerMessages(0);
        assertThat(properties.getCompactionTriggerMessages()).isZero();
        assertThat(CompactionPolicy.resolve(
                1_000_000,
                properties.getCompactionTriggerRatio(),
                properties.getCompactionTriggerTokens(),
                properties.getCompactionTriggerTokensFloor(),
                properties.getCompactionTriggerMessages())
                .messagesConditionDisabled()).isTrue();

        properties.setCompactionTriggerMessages(-9);
        assertThat(properties.getCompactionTriggerMessages()).isZero();
    }

    @Test
    void propertiesDefaultsFollowTheWindowHalfPolicy() {
        AgentScopeProperties properties = new AgentScopeProperties();

        assertThat(properties.getCompactionTriggerRatio()).isEqualTo(0.5);
        assertThat(properties.getCompactionTriggerTokensFloor()).isEqualTo(60_000);
        assertThat(properties.getCompactionTriggerMessages()).isEqualTo(400);
        // 显式兜底值只在 ratio<=0 时生效
        assertThat(properties.getCompactionTriggerTokens()).isEqualTo(48_000);
    }
}
