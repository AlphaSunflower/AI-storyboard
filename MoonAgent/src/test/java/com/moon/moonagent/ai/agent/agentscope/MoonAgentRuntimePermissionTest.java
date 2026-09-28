package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.AgentApprovalMode;
import io.agentscope.core.permission.PermissionMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MoonAgentRuntimePermissionTest {

    @Test
    void fullAccessMapsToAgentScopeBypass() {
        assertThat(MoonAgentRuntime.permissionContext(AgentApprovalMode.FULL_ACCESS).getMode())
                .isEqualTo(PermissionMode.BYPASS);
    }

    @Test
    void approvalModesKeepHighRiskToolsBehindAskRules() {
        for (AgentApprovalMode mode : new AgentApprovalMode[]{
                AgentApprovalMode.PLAN_APPROVAL,
                AgentApprovalMode.REQUEST_APPROVAL}) {
            var context = MoonAgentRuntime.permissionContext(mode);
            assertThat(context.getMode()).isEqualTo(PermissionMode.DEFAULT);
            assertThat(context.getAskRules()).containsKeys(
                    "generate_image",
                    "generate_image_batch",
                    "generate_video",
                    "clear_project_storyboard",
                    "canvas_execute_graph");
        }
    }
}
