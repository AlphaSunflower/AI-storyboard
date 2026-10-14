package com.moon.moonagent.ai.agent.agentscope;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolCallStateGuardTest {

    private final ToolCallStateGuard guard = new ToolCallStateGuard();

    /** 要求 1：begin → complete(ok) 前进到终态，stateOf 可诊断。 */
    @Test
    void beginThenCompleteAdvancesToTerminalState() {
        guard.begin("turn-1", "call-1");
        assertThat(guard.stateOf("turn-1", "call-1")).isEqualTo("RUNNING");

        ToolCallStateGuard.ToolCallState before = guard.complete(
                "turn-1", "call-1", ToolResultContract.OK);

        assertThat(before).isEqualTo(ToolCallStateGuard.ToolCallState.RUNNING);
        assertThat(guard.stateOf("turn-1", "call-1")).isEqualTo("SUCCESS");
        assertThat(guard.state("turn-1", "call-1").terminal()).isTrue();
    }

    /** 要求 2：同一调用第二次 complete 必须显式失败，消息里带 turnId/toolCallId/from/to。 */
    @Test
    void secondCompleteOnSameCallFailsLoudly() {
        guard.begin("turn-1", "call-1");
        guard.complete("turn-1", "call-1", ToolResultContract.OK);

        assertThatThrownBy(() -> guard.complete("turn-1", "call-1", ToolResultContract.ERROR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("turnId=turn-1")
                .hasMessageContaining("toolCallId=call-1")
                .hasMessageContaining("from=SUCCESS")
                .hasMessageContaining("to=ERROR");
        // 失败写入不改变既有终态
        assertThat(guard.stateOf("turn-1", "call-1")).isEqualTo("SUCCESS");
    }

    /** 要求 3：没有 begin 就 complete 是显式拒绝行为（不是静默写入）。 */
    @Test
    void completeWithoutBeginIsExplicitlyRejected() {
        assertThatThrownBy(() -> guard.complete("turn-1", "call-x", ToolResultContract.OK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("turnId=turn-1")
                .hasMessageContaining("toolCallId=call-x")
                .hasMessageContaining("from=NONE")
                .hasMessageContaining("to=SUCCESS");
        assertThat(guard.stateOf("turn-1", "call-x")).isNull();
    }

    /** 要求 3 补充：重复 begin 同样是显式失败，而不是覆盖已有状态。 */
    @Test
    void secondBeginOnSameCallFailsLoudly() {
        guard.begin("turn-1", "call-1");

        assertThatThrownBy(() -> guard.begin("turn-1", "call-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("from=RUNNING")
                .hasMessageContaining("to=RUNNING");
    }

    /** 要求 4：契约状态到生命周期终态的映射。 */
    @Test
    void mapsContractStatusesOntoLifecycleStates() {
        assertThat(terminalState("call-ok", ToolResultContract.OK))
                .isEqualTo(ToolCallStateGuard.ToolCallState.SUCCESS);
        assertThat(terminalState("call-queued", ToolResultContract.QUEUED))
                .isEqualTo(ToolCallStateGuard.ToolCallState.SUCCESS);
        assertThat(terminalState("call-partial", ToolResultContract.PARTIAL))
                .isEqualTo(ToolCallStateGuard.ToolCallState.SUCCESS);
        assertThat(terminalState("call-error", ToolResultContract.ERROR))
                .isEqualTo(ToolCallStateGuard.ToolCallState.ERROR);
        assertThat(terminalState("call-rejected", ToolResultContract.REJECTED))
                .isEqualTo(ToolCallStateGuard.ToolCallState.DENIED);
        assertThat(terminalState("call-confirm", ToolResultContract.NEEDS_CONFIRMATION))
                .isEqualTo(ToolCallStateGuard.ToolCallState.NEEDS_CONFIRMATION);
        assertThat(terminalState("call-interrupted", ToolCallStateGuard.INTERRUPTED))
                .isEqualTo(ToolCallStateGuard.ToolCallState.INTERRUPTED);
    }

    /** 词汇表之外的状态不静默降级为 ERROR，而是显式拒绝。 */
    @Test
    void rejectsStatusOutsideContractVocabulary() {
        guard.begin("turn-1", "call-1");

        assertThatThrownBy(() -> guard.complete("turn-1", "call-1", "bogus"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bogus");
        assertThat(guard.stateOf("turn-1", "call-1")).isEqualTo("RUNNING");
    }

    /** 要求 5：clear(turnId) 只清自己的轮次，别的 turn 状态存活。 */
    @Test
    void clearIsolatesTurns() {
        guard.begin("turn-1", "call-1");
        guard.complete("turn-1", "call-1", ToolResultContract.OK);
        guard.begin("turn-2", "call-2");
        guard.complete("turn-2", "call-2", ToolResultContract.ERROR);

        guard.clear("turn-1");

        assertThat(guard.stateOf("turn-1", "call-1")).isNull();
        assertThat(guard.stateOf("turn-2", "call-2")).isEqualTo("ERROR");
        assertThat(guard.snapshot()).containsOnlyKeys("turn-2");

        guard.clearAll();
        assertThat(guard.snapshot()).isEmpty();
        assertThat(guard.size()).isZero();
    }

    /** 要求 6：同一 turn 内不同 toolCallId 互不影响。 */
    @Test
    void differentToolCallsInOneTurnAreIndependent() {
        guard.begin("turn-1", "call-a");
        guard.begin("turn-1", "call-b");
        guard.complete("turn-1", "call-a", ToolResultContract.ERROR);
        guard.complete("turn-1", "call-b", ToolResultContract.OK);

        assertThat(guard.stateOf("turn-1", "call-a")).isEqualTo("ERROR");
        assertThat(guard.stateOf("turn-1", "call-b")).isEqualTo("SUCCESS");
        assertThat(guard.snapshot().get("turn-1"))
                .containsEntry("call-a", "ERROR")
                .containsEntry("call-b", "SUCCESS");
    }

    /** 终态之后出现迟到写入：assertNoLateWrite 必须报出，正常流程（RUNNING/无记录）不报。 */
    @Test
    void assertNoLateWriteOnlyFailsAfterTerminalState() {
        guard.begin("turn-1", "call-1");
        guard.assertNoLateWrite("turn-1", "call-1");
        guard.assertNoLateWrite("turn-1", "unknown");

        guard.complete("turn-1", "call-1", ToolResultContract.REJECTED);

        assertThatThrownBy(() -> guard.assertNoLateWrite("turn-1", "call-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("call-1")
                .hasMessageContaining("DENIED");
    }

    /** 身份缺失（toolCallId 为空）时守卫整体跳过，不产生合成键误报。 */
    @Test
    void skipsCallsWithoutToolCallIdentity() {
        guard.begin("turn-1", " ");
        guard.begin("turn-1", null);
        guard.complete("turn-1", " ", ToolResultContract.OK);
        guard.complete("turn-1", null, ToolResultContract.ERROR);
        guard.assertNoLateWrite("turn-1", null);

        assertThat(guard.snapshot()).isEmpty();
    }

    /**
     * 内存边界不变式：终态记录数 ≤ {@link ToolCallStateGuard#MAX_CALLS_PER_TURN}，
     * 因此总条数 ≤ 上限 + 仍在执行的调用数；RUNNING 记录永不淘汰。
     */
    @Test
    void boundsTerminalRecordsPerTurnWithoutEvictingRunningCalls() {
        int total = ToolCallStateGuard.MAX_CALLS_PER_TURN + 40;
        for (int index = 0; index < total; index++) {
            guard.begin("turn-1", "call-" + index);
            guard.complete("turn-1", "call-" + index, ToolResultContract.OK);
        }
        guard.begin("turn-1", "running-call");

        // 552 条终态被压到 512 条 + 1 条 RUNNING
        assertThat(guard.size()).isEqualTo(ToolCallStateGuard.MAX_CALLS_PER_TURN + 1);
        assertThat(guard.stateOf("turn-1", "running-call")).isEqualTo("RUNNING");
        // 最旧的终态记录先被淘汰，最近的仍有诊断价值
        assertThat(guard.stateOf("turn-1", "call-0")).isNull();
        assertThat(guard.stateOf("turn-1", "call-" + (total - 1))).isEqualTo("SUCCESS");
        // 终态记录数不超过上限（RUNNING 不占额度）
        assertThat(guard.snapshot().get("turn-1").values().stream()
                .filter(state -> !"RUNNING".equals(state)).count())
                .isLessThanOrEqualTo(ToolCallStateGuard.MAX_CALLS_PER_TURN);
    }

    private ToolCallStateGuard.ToolCallState terminalState(String toolCallId, String resultStatus) {
        guard.begin("turn-1", toolCallId);
        guard.complete("turn-1", toolCallId, resultStatus);
        return guard.state("turn-1", toolCallId);
    }
}
