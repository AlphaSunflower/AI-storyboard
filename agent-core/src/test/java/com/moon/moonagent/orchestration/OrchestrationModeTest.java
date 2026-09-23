package com.moon.moonagent.orchestration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestrationModeTest {

    @Test
    void defaultsUnknownAndBlankValuesToLegacy() {
        assertThat(OrchestrationMode.from(null)).isEqualTo(OrchestrationMode.LEGACY);
        assertThat(OrchestrationMode.from("")).isEqualTo(OrchestrationMode.LEGACY);
        assertThat(OrchestrationMode.from("bogus")).isEqualTo(OrchestrationMode.LEGACY);
    }

    @Test
    void parsesUnifiedLoopCaseInsensitively() {
        assertThat(OrchestrationMode.from("unified_loop")).isEqualTo(OrchestrationMode.UNIFIED_LOOP);
    }
}
