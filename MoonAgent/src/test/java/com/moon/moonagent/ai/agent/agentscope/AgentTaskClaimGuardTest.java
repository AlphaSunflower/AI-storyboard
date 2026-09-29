package com.moon.moonagent.ai.agent.agentscope;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTaskClaimGuardTest {

    @Test
    void delaysOnlyClaimSentenceUntilTaskContractIsResolved() {
        AgentTaskClaimGuard guard = new AgentTaskClaimGuard();

        assertThat(guard.accept("正在分析。修改要求已提交")).isEqualTo("正在分析。");
        assertThat(guard.accept(" ✅")).isEmpty();
        assertThat(guard.hasClaim()).isTrue();
        assertThat(guard.flushAll()).isEqualTo("修改要求已提交 ✅");
    }

    @Test
    void replacesClaimWhenNoTaskExists() {
        AgentTaskClaimGuard guard = new AgentTaskClaimGuard();

        assertThat(guard.accept("任务已受理。")).isEmpty();
        assertThat(guard.reject()).isEqualTo("任务未创建，请重试。");
        assertThat(guard.hasClaim()).isFalse();
    }
}
