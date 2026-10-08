package com.storyboard.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiConfigPropertiesTest {

    @Test
    void scriptTimeoutAllowsReferenceUnderstandingAndShotPlanning() {
        AiConfigProperties properties = new AiConfigProperties(new AiConfigProperties.Gateway());

        assertThat(properties.getScriptTimeout()).isEqualTo(300);
    }
}
