package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.AgentApprovalMode;
import io.agentscope.core.permission.PermissionMode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MoonAgentRuntimePermissionTest {

    @Test
    void fullAccessMapsToAgentScopeBypass() {
        var context = MoonAgentRuntime.permissionContext(AgentApprovalMode.FULL_ACCESS);
        assertThat(context.getMode())
                .isEqualTo(PermissionMode.BYPASS);
        assertThat(context.getAskRules()).isEmpty();
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

    @Test
    void storyboardOrchestrationEntrypointsBypassApproval() {
        for (AgentApprovalMode mode : new AgentApprovalMode[]{
                AgentApprovalMode.PLAN_APPROVAL,
                AgentApprovalMode.REQUEST_APPROVAL}) {
            var context = MoonAgentRuntime.permissionContext(mode);
            assertThat(context.getAllowRules()).containsKeys(
                    "start_storyboard_production",
                    "choose_storyboard_commit_mode",
                    "request_human_input");
            assertThat(context.getAskRules()).doesNotContainKey("start_storyboard_production");
        }
    }

    @Test
    void approvedPlanAutoAllowsOnlyListedHighRiskTools() {
        var context = MoonAgentRuntime.permissionContext(
                AgentApprovalMode.PLAN_APPROVAL,
                Set.of("generate_storyboard_videos", "generate_storyboard_video"));

        assertThat(context.getAllowRules()).containsKeys(
                "generate_storyboard_videos",
                "generate_storyboard_video");
        assertThat(context.getAskRules()).doesNotContainKeys(
                "generate_storyboard_videos",
                "generate_storyboard_video");
        assertThat(context.getAskRules()).containsKey("clear_project_storyboard");
    }
}
