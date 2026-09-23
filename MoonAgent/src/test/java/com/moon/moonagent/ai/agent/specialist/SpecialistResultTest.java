package com.moon.moonagent.ai.agent.specialist;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpecialistResultTest {

    @Test
    void questionResultWaitsForUserWithoutHandoff() {
        SpecialistResult result = SpecialistResult.question("请选择方案", Map.of("toolName", "generate_image"));

        assertThat(result.needsHandoff()).isFalse();
        assertThat(result.needsUserInput()).isTrue();
        assertThat(result.handoffState()).containsEntry("toolName", "generate_image");
    }

    @Test
    void failedResultIsDistinctFromSuccess() {
        SpecialistResult result = SpecialistResult.failed("tool unavailable");

        assertThat(result.isFailed()).isTrue();
        assertThat(result.needsUserInput()).isFalse();
        assertThat(result.needsHandoff()).isFalse();
    }
}
