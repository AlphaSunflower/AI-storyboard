package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.skill.SkillExpander;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentScopeToolkitFactoryTest {

    @Test
    void humanInputToolIsAlwaysRegisteredForPreflight() {
        AgentScopeInteractionTools interaction = mock(AgentScopeInteractionTools.class);
        when(interaction.tool()).thenReturn(tool("request_human_input"));
        AgentScopeToolkitFactory factory = factory(interaction);

        factory.create();

        verify(interaction).tool();
    }

    private static AgentScopeToolkitFactory factory(AgentScopeInteractionTools interaction) {
        AgentScopeSessionTools session = mock(AgentScopeSessionTools.class);
        AgentScopeStructuredTools structured = mock(AgentScopeStructuredTools.class);
        AgentScopeAssetGroupTools assetGroups = mock(AgentScopeAssetGroupTools.class);
        AgentScopeImageTools images = mock(AgentScopeImageTools.class);
        AgentScopeVideoTools videos = mock(AgentScopeVideoTools.class);
        AgentScopeStoryboardTools storyboards = mock(AgentScopeStoryboardTools.class);
        AgentScopeWorkflowTools workflows = mock(AgentScopeWorkflowTools.class);
        AgentScopeVideoMergeTools videoMerge = mock(AgentScopeVideoMergeTools.class);
        AgentScopeToolPipeline pipeline = mock(AgentScopeToolPipeline.class);
        SkillExpander expander = mock(SkillExpander.class);
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
        when(pipeline.wrap(any(AgentScopeTool.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(expander.toolCallbacks(anyList())).thenReturn(new ToolCallback[0]);

        return new AgentScopeToolkitFactory(
                session, interaction, structured, assetGroups, images, videos,
                storyboards, workflows, videoMerge, pipeline, expander);
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
}
