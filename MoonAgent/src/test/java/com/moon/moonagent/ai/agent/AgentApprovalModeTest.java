package com.moon.moonagent.ai.agent;

import com.storyboard.common.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentApprovalModeTest {

    @Test
    void legacyBlankValueFallsBackToPlanApproval() {
        assertThat(AgentApprovalMode.from(null)).isEqualTo(AgentApprovalMode.PLAN_APPROVAL);
        assertThat(AgentApprovalMode.from(" ")).isEqualTo(AgentApprovalMode.PLAN_APPROVAL);
        assertThat(AgentApprovalMode.from("unknown")).isEqualTo(AgentApprovalMode.PLAN_APPROVAL);
    }

    @Test
    void explicitUpdateRejectsUnknownModes() {
        assertThat(AgentApprovalMode.require("full_access"))
                .isEqualTo(AgentApprovalMode.FULL_ACCESS);
        assertThatThrownBy(() -> AgentApprovalMode.require("always"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("审批模式非法");
    }
}
