package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.skill.SkillExpander;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

class AgentScopeToolkitFactoryTest {

    @Test
    void humanInputToolIsAlwaysRegisteredForPreflight() {
        AgentScopeInteractionTools interaction = mock(AgentScopeInteractionTools.class);
        when(interaction.tool()).thenReturn(tool("request_human_input"));
        AgentScopeToolkitFactory factory = factory(interaction);

        factory.create();

        verify(interaction).tool();
    }

    @Test
    void documentToolsAreAlwaysRegistered() {
        AgentScopeInteractionTools interaction = mock(AgentScopeInteractionTools.class);
        when(interaction.tool()).thenReturn(tool("request_human_input"));
        AgentScopeToolkitFactory factory = factory(interaction);

        factory.create();

        assertThat(factory.activeToolkit().getToolNames())
                .contains("list_conversation_documents", "read_document");
    }

    /**
     * 分镜工具必须门控在 storyboard 技能组里，不能常驻。
     *
     * <p>2026-10-17：这 16 个工具此前无条件注册，使每一轮（哪怕只是闲聊）都带着它们，
     * 占常驻工具面的 40%。storyboard 技能的 {@code allowed-tools} 已声明这些工具，
     * 激活技能即可见——与 canvas/image 的门控方式一致。
     *
     * <p>本测试锁三件事：① 组存在且含分镜工具；② 组初始**未激活**（否则等于没门控）；
     * ③ 该组确实挂在 {@code storyboard} 技能上（否则没有任何途径激活它）。
     */
    @Test
    void storyboardToolsAreGatedBehindStoryboardSkill() {
        AgentScopeInteractionTools interaction = mock(AgentScopeInteractionTools.class);
        when(interaction.tool()).thenReturn(tool("request_human_input"));
        AgentScopeToolkitFactory factory = factory(interaction);

        factory.create();
        io.agentscope.core.tool.Toolkit toolkit = factory.activeToolkit();

        io.agentscope.core.tool.ToolGroup group =
                toolkit.getToolGroup(AgentScopeToolkitFactory.STORYBOARD_TOOLS);
        assertThat(group)
                .as("分镜工具组必须存在，否则分镜工具无处安放")
                .isNotNull();
        assertThat(group.getTools())
                .as("分镜工具必须落在这个组里")
                .contains("start_storyboard_production", "list_project_storyboard");
        assertThat(group.containsTool("merge_storyboard_videos"))
                .as("成片合并只服务分镜链路，必须与分镜同组、不得常驻")
                .isTrue();
        assertThat(group.isActive())
                .as("分镜组初始必须未激活，否则等于没有门控")
                .isFalse();
        assertThat(toolkit.findSkillToolGroupsByActivateOnSkill("storyboard"))
                .as("该组必须挂在 storyboard 技能上，否则模型没有任何途径激活它")
                .contains(AgentScopeToolkitFactory.STORYBOARD_TOOLS);
    }

    @Test
    void everyRegisteredReadOnlyToolIsAutoAllowed() {
        AgentScopeInteractionTools interaction = mock(AgentScopeInteractionTools.class);
        when(interaction.tool()).thenReturn(tool("request_human_input"));
        AgentScopeToolkitFactory factory = factory(interaction);
        factory.create();

        Set<String> readOnlyTools = factory.activeToolkit().getToolNames().stream()
                .filter(name -> factory.activeToolkit().getTool(name).isReadOnly())
                .collect(java.util.stream.Collectors.toSet());
        var permission = MoonAgentRuntime.permissionContext(
                com.moon.moonagent.ai.agent.AgentApprovalMode.PLAN_APPROVAL,
                Set.of(), readOnlyTools);

        assertThat(readOnlyTools).contains("read_document", "list_conversation_documents");
        assertThat(permission.getAllowRules()).containsKeys(readOnlyTools.toArray(String[]::new));
    }

    @Test
    void legacyCanvasPrepareToolIsNotRegistered() {
        AgentScopeInteractionTools interaction = mock(AgentScopeInteractionTools.class);
        when(interaction.tool()).thenReturn(tool("request_human_input"));
        AgentScopeCanvasTools canvasTools = mock(AgentScopeCanvasTools.class);
        when(canvasTools.tool()).thenReturn(tool(AgentScopeCanvasTools.PREPARE_CANVAS_SESSION));
        AgentScopeToolkitFactory factory = factory(interaction, mock(SkillExpander.class), canvasTools);

        factory.create();

        assertThat(factory.activeToolkit().getToolNames())
                .doesNotContain(AgentScopeCanvasTools.PREPARE_CANVAS_SESSION);
    }

    @Test
    void refreshesCanvasToolsAfterDeferredMcpConnection() {
        AgentScopeInteractionTools interaction = mock(AgentScopeInteractionTools.class);
        when(interaction.tool()).thenReturn(tool("request_human_input"));
        SkillExpander expander = mock(SkillExpander.class);
        when(expander.toolCallbacksByPrefix("canvas_"))
                .thenReturn(new ToolCallback[0])
                .thenReturn(new ToolCallback[] { callback("canvas_graph_get_context") });
        AgentScopeToolkitFactory factory = factory(
                interaction, expander, mock(AgentScopeCanvasTools.class));

        factory.create();
        assertThat(factory.activeToolkit().getToolNames()).doesNotContain("canvas_graph_get_context");

        factory.refreshCanvasTools();

        assertThat(factory.activeToolkit().getToolNames()).contains("canvas_graph_get_context");
        verify(expander, atLeastOnce()).toolCallbacksByPrefix("canvas_");
    }

    @Test
    void legacyBrowserCanvasToolsAreNotRegisteredInNewCanvasSkills() {
        AgentScopeInteractionTools interaction = mock(AgentScopeInteractionTools.class);
        when(interaction.tool()).thenReturn(tool("request_human_input"));
        SkillExpander expander = mock(SkillExpander.class);
        ToolCallback[] callbacks = {
                callback("canvas_import_media"),
                callback("canvas_graph_import_media"),
                callback("canvas_execute_graph")
        };
        when(expander.toolCallbacksByPrefix("canvas_")).thenReturn(callbacks);
        AgentScopeToolkitFactory factory = factory(interaction, expander);

        factory.create();

        assertThat(factory.activeToolkit().getToolNames())
                .contains("canvas_graph_import_media")
                .contains("canvas_execute_graph")
                .doesNotContain("canvas_import_media");
    }

    @Test
    void canvasRunStarterIsRegisteredWithoutWaitingForMcp() {
        AgentScopeInteractionTools interaction = mock(AgentScopeInteractionTools.class);
        when(interaction.tool()).thenReturn(tool("request_human_input"));
        AgentScopeCanvasRunTools runTools = mock(AgentScopeCanvasRunTools.class);
        when(runTools.tool()).thenReturn(tool(AgentScopeCanvasRunTools.CREATE_CANVAS_RUN));
        AgentScopeToolkitFactory factory = factory(
                interaction, mock(SkillExpander.class), mock(AgentScopeCanvasTools.class), runTools);

        factory.create();

        assertThat(factory.activeToolkit().getToolNames())
                .contains(AgentScopeCanvasRunTools.CREATE_CANVAS_RUN);
    }

    @Test
    void emptyProbeNeverRemovesRegisteredCanvasTools() {
        // 事故形态（2026-10-15）：画布 MCP 工具列表探测一度为空，原实现按空集合收敛，
        // 把已注册的画布工具全部摘掉，而发现记录（SkillExpander）只增不减、仍宣称画布可用，
        // 模型于是每次调用都拿到 "Tool not found"。注册表只能被非空探测替换。
        AgentScopeInteractionTools interaction = mock(AgentScopeInteractionTools.class);
        when(interaction.tool()).thenReturn(tool("request_human_input"));
        SkillExpander expander = mock(SkillExpander.class);
        ToolCallback[] withCanvas = { callback("canvas_graph_get_state") };
        ToolCallback[] empty = new ToolCallback[0];
        when(expander.toolCallbacksByPrefix("canvas_"))
                .thenReturn(withCanvas)
                .thenReturn(empty);
        AgentScopeToolkitFactory factory = factory(
                interaction, expander, mock(AgentScopeCanvasTools.class));

        factory.create();
        assertThat(factory.activeToolkit().getToolNames()).contains("canvas_graph_get_state");

        factory.refreshCanvasTools();

        assertThat(factory.activeToolkit().getToolNames()).contains("canvas_graph_get_state");
    }

    @Test
    void toolkitRegistryIsTheAuthorityForCanvasAvailability() {
        AgentScopeInteractionTools interaction = mock(AgentScopeInteractionTools.class);
        when(interaction.tool()).thenReturn(tool("request_human_input"));
        SkillExpander expander = mock(SkillExpander.class);
        ToolCallback[] withCanvas = {
                callback("canvas_graph_get_state"), callback("canvas_graph_layout_graph")};
        ToolCallback[] empty = new ToolCallback[0];
        when(expander.toolCallbacksByPrefix("canvas_"))
                .thenReturn(empty)
                .thenReturn(withCanvas);
        AgentScopeToolkitFactory factory = factory(
                interaction, expander, mock(AgentScopeCanvasTools.class));

        factory.create();
        // create_run 恒注册，但它不代表画布链路可用：就绪判定只能看真实注册表。
        assertThat(factory.activeToolkit().getToolNames())
                .contains(AgentScopeCanvasRunTools.CREATE_CANVAS_RUN);
        assertThat(factory.hasCanvasTools()).isFalse();
        assertThat(factory.hasTool("canvas_graph_get_state")).isFalse();

        factory.refreshCanvasTools();

        assertThat(factory.hasCanvasTools()).isTrue();
        assertThat(factory.hasTool("canvas_graph_get_state")).isTrue();
        assertThat(factory.canvasToolNames())
                .contains("canvas_graph_get_state", "canvas_graph_layout_graph");
    }

    @Test
    void planAndTodoToolsAreGuardedExactlyOnceOnBind() {
        AgentScopeInteractionTools interaction = mock(AgentScopeInteractionTools.class);
        when(interaction.tool()).thenReturn(tool("request_human_input"));
        AgentScopeToolkitFactory factory = factory(interaction);

        Toolkit toolkit = factory.create();
        CanvasPlanToolGuard.GUARDED_TOOL_NAMES.forEach(name -> toolkit.registerAgentTool(tool(name)));

        factory.bindAgentToolkit(toolkit);
        // 实测启动日志里 bindAgentToolkit 会被调用两次：重复绑定必须幂等，不能套娃
        factory.bindAgentToolkit(toolkit);

        for (String name : CanvasPlanToolGuard.GUARDED_TOOL_NAMES) {
            assertThat(toolkit.getTool(name))
                    .as("%s 应被替换成画布轮守卫", name)
                    .isInstanceOf(CanvasPlanToolGuard.class);
            assertThat(((CanvasPlanToolGuard) toolkit.getTool(name)).delegate())
                    .as("%s 不得被包装两层", name)
                    .isNotInstanceOf(CanvasPlanToolGuard.class);
        }
        assertThat(toolkit.getToolNames()).contains("plan_enter", "todo_write");
    }

    @Test
    void bindingWithoutPlanToolsIsHarmless() {
        AgentScopeInteractionTools interaction = mock(AgentScopeInteractionTools.class);
        when(interaction.tool()).thenReturn(tool("request_human_input"));
        AgentScopeToolkitFactory factory = factory(interaction);

        Toolkit toolkit = factory.create();
        factory.bindAgentToolkit(toolkit);

        assertThat(toolkit.getToolNames()).doesNotContain("plan_enter", "todo_write");
    }

    private static AgentScopeToolkitFactory factory(AgentScopeInteractionTools interaction) {
        return factory(interaction, mock(SkillExpander.class), mock(AgentScopeCanvasTools.class));
    }

    private static AgentScopeToolkitFactory factory(AgentScopeInteractionTools interaction,
                                                    SkillExpander expander) {
        return factory(interaction, expander, mock(AgentScopeCanvasTools.class));
    }

    private static AgentScopeToolkitFactory factory(AgentScopeInteractionTools interaction,
                                                    SkillExpander expander,
                                                    AgentScopeCanvasTools canvasTools) {
        return factory(interaction, expander, canvasTools, mock(AgentScopeCanvasRunTools.class));
    }

    private static AgentScopeToolkitFactory factory(AgentScopeInteractionTools interaction,
                                                    SkillExpander expander,
                                                    AgentScopeCanvasTools canvasTools,
                                                    AgentScopeCanvasRunTools runTools) {
        AgentScopeSessionTools session = mock(AgentScopeSessionTools.class);
        AgentScopeStructuredTools structured = mock(AgentScopeStructuredTools.class);
        AgentScopeAssetGroupTools assetGroups = mock(AgentScopeAssetGroupTools.class);
        AgentScopeImageTools images = mock(AgentScopeImageTools.class);
        AgentScopeVideoTools videos = mock(AgentScopeVideoTools.class);
        AgentScopeJourneyTools journeys = mock(AgentScopeJourneyTools.class);
        when(journeys.tool()).thenReturn(tool(AgentScopeJourneyTools.PRODUCE_VIDEO_JOURNEY));
        AgentScopeStoryboardTools storyboards = mock(AgentScopeStoryboardTools.class);
        AgentScopeWorkflowTools workflows = mock(AgentScopeWorkflowTools.class);
        AgentScopeVideoMergeTools videoMerge = mock(AgentScopeVideoMergeTools.class);
        AgentScopeCreativeTools creative = mock(AgentScopeCreativeTools.class);
        AgentScopeToolPipeline pipeline = mock(AgentScopeToolPipeline.class);
        when(session.tools()).thenReturn(List.of());
        when(structured.tools()).thenReturn(List.of());
        when(assetGroups.tool()).thenReturn(tool("list_asset_groups"));
        when(interaction.storyboardCommitTool()).thenReturn(tool("choose_storyboard_commit_mode"));
        when(images.tool()).thenReturn(tool("generate_image"));
        when(images.batchTool()).thenReturn(tool("generate_image_batch"));
        when(videos.tool()).thenReturn(tool("generate_video"));
        when(storyboards.tools()).thenReturn(List.of(
                tool("start_storyboard_production"), tool("list_project_storyboard")));
        when(workflows.tool()).thenReturn(tool("resolve_agent_workflow"));
        when(videoMerge.tool()).thenReturn(tool("merge_storyboard_videos"));
        when(creative.tools()).thenReturn(List.of());
        AgentScopeDocumentTools documentTools = mock(AgentScopeDocumentTools.class);
        when(documentTools.tools()).thenReturn(List.of(
                tool("list_conversation_documents", true), tool("read_document", true)));
        when(canvasTools.tool()).thenReturn(tool(AgentScopeCanvasTools.PREPARE_CANVAS_SESSION));
        when(runTools.tool()).thenReturn(tool(AgentScopeCanvasRunTools.CREATE_CANVAS_RUN));
        when(pipeline.wrap(any(AgentScopeTool.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(expander.toolCallbacks(anyList())).thenReturn(new ToolCallback[0]);
        when(pipeline.wrapGeneric(any()))
                .thenAnswer(invocation -> tool(
                        ((io.agentscope.core.tool.ToolBase) invocation.getArgument(0)).getName()));

        return new AgentScopeToolkitFactory(
                session, interaction, structured, assetGroups, images, videos, journeys,
                storyboards, workflows, videoMerge, creative, canvasTools, runTools,
                documentTools, mock(AgentCanvasRunService.class), pipeline, expander,
                new AgentScopeProperties());
    }

    private static AgentScopeTool tool(String name) {
        return tool(name, false);
    }

    private static AgentScopeTool tool(String name, boolean readOnly) {
        return new AgentScopeTool(
                name,
                name,
                AgentScopeTool.schema(Map.of(), List.of()),
                readOnly,
                false,
                false,
                (input, param) -> Map.of());
    }

    private static ToolCallback callback(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(definition.description()).thenReturn(name);
        when(definition.inputSchema()).thenReturn("{\"type\":\"object\",\"properties\":{}}");
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }
}
