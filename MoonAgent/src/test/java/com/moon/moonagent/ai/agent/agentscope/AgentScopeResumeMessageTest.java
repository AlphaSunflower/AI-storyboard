package com.moon.moonagent.ai.agent.agentscope;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopeResumeMessageTest {

    @Test
    void externalToolResumeUsesToolRoleForToolResultBlock() {
        Msg message = MoonAgentRuntime.resumeMessage(
                new AgentScopeCheckpointService.ResumeValue(
                        "external", "call-1", "request_human_input", "append", true));

        assertThat(message.getRole()).isEqualTo(MsgRole.TOOL);
        assertThat(message.getContent()).singleElement()
                .isInstanceOf(ToolResultBlock.class);
    }
}
