package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.AgentAiConfigProperties;
import com.moon.moonagent.ai.agent.skill.SkillExpander;
import io.agentscope.core.tool.Toolkit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(prefix = "ai.agent.agentscope", name = "enabled",
        havingValue = "true")
public class AgentScopeToolkitFactory {

    public static final String IMAGE_TOOLS = "image-tools";
    public static final String VIDEO_TOOLS = "video-tools";

    private final AgentScopeSessionTools sessionTools;
    private final AgentScopeInteractionTools interactionTools;
    private final AgentScopeStructuredTools structuredTools;
    private final AgentScopeAssetGroupTools assetGroupTools;
    private final AgentScopeImageTools imageTools;
    private final AgentScopeVideoTools videoTools;
    private final AgentScopeStoryboardTools storyboardTools;
    private final AgentScopeToolPipeline toolPipeline;
    private final SkillExpander skillExpander;
    private final AgentAiConfigProperties agentConfig;

    public AgentScopeToolkitFactory(AgentScopeSessionTools sessionTools,
                                    AgentScopeInteractionTools interactionTools,
                                    AgentScopeStructuredTools structuredTools,
                                    AgentScopeAssetGroupTools assetGroupTools,
                                    AgentScopeImageTools imageTools,
                                    AgentScopeVideoTools videoTools,
                                    AgentScopeStoryboardTools storyboardTools,
                                    AgentScopeToolPipeline toolPipeline,
                                    SkillExpander skillExpander,
                                    AgentAiConfigProperties agentConfig) {
        this.sessionTools = sessionTools;
        this.interactionTools = interactionTools;
        this.structuredTools = structuredTools;
        this.assetGroupTools = assetGroupTools;
        this.imageTools = imageTools;
        this.videoTools = videoTools;
        this.storyboardTools = storyboardTools;
        this.toolPipeline = toolPipeline;
        this.skillExpander = skillExpander;
        this.agentConfig = agentConfig;
    }

    public Toolkit create() {
        Toolkit toolkit = new Toolkit();

        // Read-only discovery stays available before a skill is activated. Human input can be
        // disabled temporarily so long-running media/storyboard work cannot stop on a card.
        sessionTools.tools().forEach(tool -> register(toolkit, tool));
        structuredTools.tools().forEach(tool -> register(toolkit, tool));
        register(toolkit, assetGroupTools.tool());
        register(toolkit, interactionTools.storyboardCommitTool());
        if (agentConfig.isHumanInputEnabled()) {
            register(toolkit, interactionTools.tool());
        }
        storyboardTools.tools().forEach(tool -> register(toolkit, tool));
        ToolCallback[] canvasCallbacks = skillExpander.toolCallbacksByPrefix("canvas_");
        if (canvasCallbacks == null) canvasCallbacks = new ToolCallback[0];
        for (ToolCallback canvasTool : canvasCallbacks) {
            register(toolkit, toolPipeline.wrapGeneric(new SpringAiToolAdapter(canvasTool)));
        }

        toolkit.createSkillToolGroup(
                IMAGE_TOOLS, "Image generation and reference-edit tools", false, "image");
        toolkit.registration()
                .agentTool(toolPipeline.wrap(imageTools.tool()))
                .group(IMAGE_TOOLS).apply();
        toolkit.registration()
                .agentTool(toolPipeline.wrap(imageTools.batchTool()))
                .group(IMAGE_TOOLS).apply();
        ToolCallback[] imageCallbacks = skillExpander.toolCallbacks(List.of("refineImage"));
        if (imageCallbacks.length > 0) {
            toolkit.registration()
                    .agentTool(toolPipeline.wrapGeneric(new SpringAiToolAdapter(imageCallbacks[0])))
                    .group(IMAGE_TOOLS)
                    .apply();
        }

        toolkit.createSkillToolGroup(
                VIDEO_TOOLS, "Asynchronous video generation tools", false, "video");
        toolkit.registration()
                .agentTool(toolPipeline.wrap(videoTools.tool()))
                .group(VIDEO_TOOLS).apply();
        return toolkit;
    }

    private void register(Toolkit toolkit, AgentScopeTool tool) {
        toolkit.registerAgentTool(toolPipeline.wrap(tool));
    }

    private void register(Toolkit toolkit, io.agentscope.core.tool.AgentTool tool) {
        toolkit.registerAgentTool(tool);
    }
}
