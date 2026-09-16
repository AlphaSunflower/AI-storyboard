package com.moon.moonagent.ai.agent.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentChatServiceImplMediaTypeTest {

    @Test
    void resolvesSupportedReferenceMediaKinds() {
        assertThat(AgentChatServiceImpl.referenceMediaType("image/png")).isEqualTo("image");
        assertThat(AgentChatServiceImpl.referenceMediaType("video/mp4")).isEqualTo("video");
        assertThat(AgentChatServiceImpl.referenceMediaType("audio/mpeg")).isEqualTo("audio");
        assertThat(AgentChatServiceImpl.referenceMediaType("application/pdf")).isEmpty();
    }
}
