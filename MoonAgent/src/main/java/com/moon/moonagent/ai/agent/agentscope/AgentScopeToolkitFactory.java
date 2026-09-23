package com.moon.moonagent.ai.agent.agentscope;

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
    private final SkillExpander skillExpander;

    public AgentScopeToolkitFactory(AgentScopeSessionTools sessionTools,
                                    AgentScopeInteractionTools interactionTools,
                                    AgentScopeStructuredTools structuredTools,
                                    AgentScopeAssetGroupTools assetGroupTools,
                                    AgentScopeImageTools imageTools,
                                    AgentScopeVideoTools videoTools,
                                    SkillExpander skillExpander) {
        this.sessionTools = sessionTools;
        this.interactionTools = interactionTools;
        this.structuredTools = structuredTools;
        this.assetGroupTools = assetGroupTools;
        this.imageTools = imageTools;
        this.videoTools = videoTools;
        this.skillExpander = skillExpander;
    }

    public Toolkit create() {
        Toolkit toolkit = new Toolkit();

        // Read-only discovery and one focused clarification tool stay available before a
        // skill is activated. Media tools remain hidden until their skill group is loaded.
        sessionTools.tools().forEach(toolkit::registerAgentTool);
        structuredTools.tools().forEach(toolkit::registerAgentTool);
        toolkit.registerAgentTool(assetGroupTools.tool());
        toolkit.registerAgentTool(interactionTools.tool());

        toolkit.createSkillToolGroup(
                IMAGE_TOOLS, "Image generation and reference-edit tools", false, "pic");
        toolkit.registration().agentTool(imageTools.tool()).group(IMAGE_TOOLS).apply();
        toolkit.registration().agentTool(imageTools.batchTool()).group(IMAGE_TOOLS).apply();
        ToolCallback[] imageCallbacks = skillExpander.toolCallbacks(List.of("refineImage"));
        if (imageCallbacks.length > 0) {
            toolkit.registration()
                    .agentTool(new SpringAiToolAdapter(imageCallbacks[0]))
                    .group(IMAGE_TOOLS)
                    .apply();
        }

        toolkit.createSkillToolGroup(
                VIDEO_TOOLS, "Asynchronous video generation tools", false, "video");
        toolkit.registration().agentTool(videoTools.tool()).group(VIDEO_TOOLS).apply();
        return toolkit;
    }
}
