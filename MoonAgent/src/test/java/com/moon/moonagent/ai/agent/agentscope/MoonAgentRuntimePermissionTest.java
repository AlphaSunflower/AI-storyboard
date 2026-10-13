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
    void ignoreGenerationPlanExemptsMediaToolsFromAskRules() {
        for (AgentApprovalMode mode : new AgentApprovalMode[]{
                AgentApprovalMode.PLAN_APPROVAL,
                AgentApprovalMode.REQUEST_APPROVAL}) {
            var context = MoonAgentRuntime.permissionContext(mode, Set.of(), Set.of(), true);
            assertThat(context.getAllowRules()).containsKeys(
                    "generate_image",
                    "generate_image_batch",
                    "generate_video",
                    "generate_storyboard_image",
                    "generate_storyboard_images",
                    "generate_storyboard_video",
                    "generate_storyboard_videos");
            assertThat(context.getAskRules()).doesNotContainKeys(
                    "generate_image", "generate_video", "generate_storyboard_videos");
            // 只豁免媒体生成：其他高风险工具照旧需要确认
            assertThat(context.getAskRules()).containsKeys(
                    "write_file", "clear_project_storyboard", "canvas_delete_nodes");
        }
    }

    @Test
    void withoutTheToggleMediaToolsStayBehindAskRules() {
        var context = MoonAgentRuntime.permissionContext(AgentApprovalMode.PLAN_APPROVAL,
                Set.of(), Set.of(), false);
        assertThat(context.getAskRules()).containsKeys("generate_image", "generate_video");
        assertThat(context.getAllowRules()).doesNotContainKey("generate_image");
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
                    "clear_project_storyboard");
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
    void canvasRunNodeOperationsBypassApproval() {
        for (AgentApprovalMode mode : new AgentApprovalMode[]{
                AgentApprovalMode.PLAN_APPROVAL,
                AgentApprovalMode.REQUEST_APPROVAL}) {
            var context = MoonAgentRuntime.permissionContext(mode);
            assertThat(context.getAllowRules()).containsKeys(
                    AgentScopeCanvasRunTools.CREATE_CANVAS_RUN,
                    "canvas_graph_create_node",
                    "canvas_graph_create_nodes",
                    "canvas_graph_import_media",
                    "canvas_graph_create_edge",
                    "canvas_graph_layout_graph",
                    "canvas_execute_node",
                    "canvas_execute_graph",
                    "todo_write",
                    "plan_write");
            assertThat(context.getAskRules()).doesNotContainKeys(
                    "canvas_graph_create_node",
                    "canvas_graph_import_media",
                    "canvas_graph_create_edge",
                    "canvas_execute_graph",
                    "todo_write");
        }
    }

    @Test
    void canvasReadOnlyToolsBypassApproval() {
        for (AgentApprovalMode mode : new AgentApprovalMode[]{
                AgentApprovalMode.PLAN_APPROVAL,
                AgentApprovalMode.REQUEST_APPROVAL}) {
            var context = MoonAgentRuntime.permissionContext(mode);
            assertThat(context.getAllowRules()).containsKeys(
                    "canvas_get_context",
                    "canvas_get_browser_context",
                    "canvas_list_node_types",
                    "canvas_get_state",
                    "canvas_list_nodes",
                    "canvas_get_node",
                    "canvas_list_edges",
                    "canvas_get_selection",
                    "canvas_get_node_model_config",
                    "canvas_get_execution");
            assertThat(context.getAskRules()).doesNotContainKeys(
                    "canvas_get_context",
                    "canvas_list_node_types",
                    "canvas_get_state");
        }
    }

    @Test
    void allRegisteredReadOnlyToolsBypassApproval() {
        var context = MoonAgentRuntime.permissionContext(
                AgentApprovalMode.PLAN_APPROVAL,
                Set.of(),
                Set.of("read_document", "list_conversation_documents", "some_future_reader"));

        assertThat(context.getAllowRules()).containsKeys(
                "read_document",
                "list_conversation_documents",
                "some_future_reader");
        assertThat(context.getAskRules()).doesNotContainKeys(
                "read_document",
                "list_conversation_documents",
                "some_future_reader");
    }

    @Test
    void nonDestructiveInternalHelpersBypassApproval() {
        var context = MoonAgentRuntime.permissionContext(AgentApprovalMode.PLAN_APPROVAL);

        assertThat(context.getAllowRules()).containsKeys(
                "resolve_agent_workflow",
                "agent_spawn",
                "agent_send",
                "canvas_undo",
                "canvas_redo",
                "canvas_image_crop",
                "canvas_image_split",
                "canvas_image_adjust",
                "reorder_storyboard_draft",
                "save_timeline_manifest",
                "update_creative_context");
        assertThat(context.getAskRules()).doesNotContainKeys(
                "resolve_agent_workflow",
                "agent_spawn",
                "agent_send",
                "canvas_undo",
                "canvas_image_crop");
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
