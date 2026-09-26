package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.AgentAiConfigProperties;
import com.moon.moonagent.ai.agent.skill.SkillExpander;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentScopeToolkitFactoryTest {

    @Test
    void humanInputToolIsNotRegisteredWhileDisabled() {
        AgentScopeInteractionTools interaction = mock(AgentScopeInteractionTools.class);
        AgentScopeToolkitFactory factory = factory(interaction, false);

        factory.create();

        verify(interaction, never()).tool();
    }

    @Test
    void humanInputToolIsRegisteredWhenExplicitlyEnabled() {
        AgentScopeInteractionTools interaction = mock(AgentScopeInteractionTools.class);
        when(interaction.tool()).thenReturn(tool("request_human_input"));
        AgentScopeToolkitFactory factory = factory(interaction, true);

        factory.create();

        verify(interaction).tool();
    }

    private static AgentScopeToolkitFactory factory(AgentScopeInteractionTools interaction,
                                                    boolean humanInputEnabled) {
        AgentScopeSessionTools session = mock(AgentScopeSessionTools.class);
        AgentScopeStructuredTools structured = mock(AgentScopeStructuredTools.class);
        AgentScopeAssetGroupTools assetGroups = mock(AgentScopeAssetGroupTools.class);
        AgentScopeImageTools images = mock(AgentScopeImageTools.class);
        AgentScopeVideoTools videos = mock(AgentScopeVideoTools.class);
        AgentScopeStoryboardTools storyboards = mock(AgentScopeStoryboardTools.class);
        AgentScopeToolPipeline pipeline = mock(AgentScopeToolPipeline.class);
        SkillExpander expander = mock(SkillExpander.class);
        AgentAiConfigProperties config = new AgentAiConfigProperties();
        config.setHumanInputEnabled(humanInputEnabled);

        when(session.tools()).thenReturn(List.of());
        when(structured.tools()).thenReturn(List.of());
        when(assetGroups.tool()).thenReturn(tool("list_asset_groups"));
        when(interaction.storyboardCommitTool()).thenReturn(tool("choose_storyboard_commit_mode"));
        when(images.tool()).thenReturn(tool("generate_image"));
        when(images.batchTool()).thenReturn(tool("generate_image_batch"));
        when(videos.tool()).thenReturn(tool("generate_video"));
        when(storyboards.tools()).thenReturn(List.of());
        when(pipeline.wrap(any(AgentScopeTool.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(expander.toolCallbacks(anyList())).thenReturn(new ToolCallback[0]);

        return new AgentScopeToolkitFactory(
                session, interaction, structured, assetGroups, images, videos,
                storyboards, pipeline, expander, config);
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
