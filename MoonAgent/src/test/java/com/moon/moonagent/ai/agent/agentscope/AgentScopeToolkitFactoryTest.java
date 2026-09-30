package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.skill.SkillExpander;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

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
    void canvasFallbackToolIsAlwaysRegistered() {
        AgentScopeInteractionTools interaction = mock(AgentScopeInteractionTools.class);
        when(interaction.tool()).thenReturn(tool("request_human_input"));
        AgentScopeCanvasTools canvasTools = mock(AgentScopeCanvasTools.class);
        when(canvasTools.tool()).thenReturn(tool(AgentScopeCanvasTools.PREPARE_CANVAS_SESSION));
        AgentScopeToolkitFactory factory = factory(interaction, mock(SkillExpander.class), canvasTools);

        factory.create();

        assertThat(factory.activeToolkit().getToolNames())
                .contains(AgentScopeCanvasTools.PREPARE_CANVAS_SESSION);
        verify(canvasTools).tool();
    }

    @Test
    void refreshesCanvasToolsAfterDeferredMcpConnection() {
        AgentScopeInteractionTools interaction = mock(AgentScopeInteractionTools.class);
        when(interaction.tool()).thenReturn(tool("request_human_input"));
        SkillExpander expander = mock(SkillExpander.class);
        when(expander.toolCallbacksByPrefix("canvas_"))
                .thenReturn(new ToolCallback[0])
                .thenReturn(new ToolCallback[] { callback("canvas_get_context") });
        AgentScopeToolkitFactory factory = factory(
                interaction, expander, mock(AgentScopeCanvasTools.class));

        factory.create();
        assertThat(factory.activeToolkit().getToolNames()).doesNotContain("canvas_get_context");

        factory.refreshCanvasTools();

        assertThat(factory.activeToolkit().getToolNames()).contains("canvas_get_context");
        verify(expander, atLeastOnce()).toolCallbacksByPrefix("canvas_");
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
        AgentScopeSessionTools session = mock(AgentScopeSessionTools.class);
        AgentScopeStructuredTools structured = mock(AgentScopeStructuredTools.class);
        AgentScopeAssetGroupTools assetGroups = mock(AgentScopeAssetGroupTools.class);
        AgentScopeImageTools images = mock(AgentScopeImageTools.class);
        AgentScopeVideoTools videos = mock(AgentScopeVideoTools.class);
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
        when(storyboards.tools()).thenReturn(List.of());
        when(workflows.tool()).thenReturn(tool("resolve_agent_workflow"));
        when(videoMerge.tool()).thenReturn(tool("merge_storyboard_videos"));
        when(creative.tools()).thenReturn(List.of());
        when(canvasTools.tool()).thenReturn(tool(AgentScopeCanvasTools.PREPARE_CANVAS_SESSION));
        when(pipeline.wrap(any(AgentScopeTool.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(expander.toolCallbacks(anyList())).thenReturn(new ToolCallback[0]);
        when(pipeline.wrapGeneric(any()))
                .thenAnswer(invocation -> tool("canvas_get_context"));

        return new AgentScopeToolkitFactory(
                session, interaction, structured, assetGroups, images, videos,
                storyboards, workflows, videoMerge, creative, canvasTools, pipeline, expander);
    }

    private static AgentScopeTool tool(String name) {
        return new AgentScopeTool(
                name,
                name,
                AgentScopeTool.schema(Map.of(), List.of()),
                false,
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
