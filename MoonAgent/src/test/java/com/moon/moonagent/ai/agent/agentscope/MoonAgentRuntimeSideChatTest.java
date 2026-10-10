package com.moon.moonagent.ai.agent.agentscope;

import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MoonAgentRuntimeSideChatTest {

    @Test
    void cloneStableStateCopiesSummaryAndContextWithANewSessionId() {
        AgentState source = AgentState.builder()
                .sessionId("source-conversation")
                .userId("user-1")
                .summary("已压缩摘要")
                .addMessage(new UserMessage("第一轮问题"))
                .addMessage(new AssistantMessage("第一轮回答"))
                .build();

        AgentState clone = MoonAgentRuntime.cloneStableState(
                source, "side-conversation", "user-1");

        assertThat(clone.getSessionId()).isEqualTo("side-conversation");
        assertThat(clone.getUserId()).isEqualTo("user-1");
        assertThat(clone.getSummary()).isEqualTo("已压缩摘要");
        assertThat(clone.getContext().stream().map(Msg::getTextContent))
                .containsExactly("第一轮问题", "第一轮回答");
    }
}
