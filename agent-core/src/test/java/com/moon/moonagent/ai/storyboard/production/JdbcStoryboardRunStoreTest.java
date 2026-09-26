package com.moon.moonagent.ai.storyboard.production;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcStoryboardRunStoreTest {

    @Test
    void canonicalRunIdRestoresUuidHyphensForLegacyApprovalPayloads() {
        assertThat(JdbcStoryboardRunStore.canonicalRunId(
                "b66b3892b3ff43d892e2e781bac2a7e4"))
                .isEqualTo("b66b3892-b3ff-43d8-92e2-e781bac2a7e4");
        assertThat(JdbcStoryboardRunStore.canonicalRunId(
                "b66b3892-b3ff-43d8-92e2-e781bac2a7e4"))
                .isEqualTo("b66b3892-b3ff-43d8-92e2-e781bac2a7e4");
    }
}
