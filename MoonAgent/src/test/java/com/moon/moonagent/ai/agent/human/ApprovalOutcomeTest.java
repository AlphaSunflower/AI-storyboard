package com.moon.moonagent.ai.agent.human;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalOutcomeTest {

    @Test
    void onlyAllowedExecutes() {
        assertThat(ApprovalOutcome.ALLOWED.allowsExecution()).isTrue();
        assertThat(ApprovalOutcome.REJECTED.allowsExecution()).isFalse();
        assertThat(ApprovalOutcome.CANCELLED.allowsExecution()).isFalse();
        assertThat(ApprovalOutcome.UNAVAILABLE.allowsExecution()).isFalse();
    }

    @Test
    void unknownOrMissingActionFailsClosed() {
        assertThat(ApprovalOutcome.fromAction(null)).isEqualTo(ApprovalOutcome.UNAVAILABLE);
        assertThat(ApprovalOutcome.fromAction("")).isEqualTo(ApprovalOutcome.UNAVAILABLE);
        assertThat(ApprovalOutcome.fromAction("whatever")).isEqualTo(ApprovalOutcome.UNAVAILABLE);
    }

    @Test
    void actionAliasesMapToClosedVocabulary() {
        assertThat(ApprovalOutcome.fromAction("approve")).isEqualTo(ApprovalOutcome.ALLOWED);
        assertThat(ApprovalOutcome.fromAction("ALLOW")).isEqualTo(ApprovalOutcome.ALLOWED);
        assertThat(ApprovalOutcome.fromAction("reject")).isEqualTo(ApprovalOutcome.REJECTED);
        assertThat(ApprovalOutcome.fromAction("cancel")).isEqualTo(ApprovalOutcome.CANCELLED);
        assertThat(ApprovalOutcome.ALLOWED.wireValue()).isEqualTo("allowed");
        assertThat(ApprovalOutcome.UNAVAILABLE.wireValue()).isEqualTo("unavailable");
    }
}
