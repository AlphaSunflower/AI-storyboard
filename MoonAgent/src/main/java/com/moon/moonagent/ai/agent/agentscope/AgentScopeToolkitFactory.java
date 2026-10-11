package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.skill.SkillExpander;
import io.agentscope.core.tool.Toolkit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(prefix = "ai.agent.agentscope", name = "enabled",
        havingValue = "true")
public class AgentScopeToolkitFactory {

    public static final String IMAGE_TOOLS = "image-tools";
    public static final String VIDEO_TOOLS = "video-tools";
    public static final String CANVAS_DESIGN_TOOLS = "canvas-design-tools";
    public static final String CANVAS_RUN_TOOLS = "canvas-run-tools";

    private final AgentScopeSessionTools sessionTools;
    private final AgentScopeInteractionTools interactionTools;
    private final AgentScopeStructuredTools structuredTools;
    private final AgentScopeAssetGroupTools assetGroupTools;
    private final AgentScopeImageTools imageTools;
    private final AgentScopeVideoTools videoTools;
    private final AgentScopeStoryboardTools storyboardTools;
    private final AgentScopeWorkflowTools workflowTools;
    private final AgentScopeVideoMergeTools videoMergeTools;
    private final AgentScopeCreativeTools creativeTools;
    private final AgentScopeCanvasTools canvasTools;
    private final AgentScopeCanvasRunTools canvasRunTools;
    private final AgentScopeDocumentTools documentTools;
    @Autowired(required = false)
    private AgentScopeSandboxTools sandboxTools;
    @Autowired(required = false)
    private AgentScopeWebTools webTools;
    private final AgentCanvasRunService canvasRuns;
    private final AgentScopeToolPipeline toolPipeline;
    private final SkillExpander skillExpander;
    private volatile Toolkit activeToolkit;

    public AgentScopeToolkitFactory(AgentScopeSessionTools sessionTools,
                                    AgentScopeInteractionTools interactionTools,
                                    AgentScopeStructuredTools structuredTools,
                                    AgentScopeAssetGroupTools assetGroupTools,
                                    AgentScopeImageTools imageTools,
                                    AgentScopeVideoTools videoTools,
                                    AgentScopeStoryboardTools storyboardTools,
                                    AgentScopeWorkflowTools workflowTools,
                                    AgentScopeVideoMergeTools videoMergeTools,
                                    AgentScopeCreativeTools creativeTools,
                                    AgentScopeCanvasTools canvasTools,
                                    AgentScopeCanvasRunTools canvasRunTools,
                                    AgentScopeDocumentTools documentTools,
                                    AgentCanvasRunService canvasRuns,
                                    AgentScopeToolPipeline toolPipeline,
                                    SkillExpander skillExpander) {
        this.sessionTools = sessionTools;
        this.interactionTools = interactionTools;
        this.structuredTools = structuredTools;
        this.assetGroupTools = assetGroupTools;
        this.imageTools = imageTools;
        this.videoTools = videoTools;
        this.storyboardTools = storyboardTools;
        this.workflowTools = workflowTools;
        this.videoMergeTools = videoMergeTools;
        this.creativeTools = creativeTools;
        this.canvasTools = canvasTools;
        this.canvasRunTools = canvasRunTools;
        this.documentTools = documentTools;
        this.canvasRuns = canvasRuns;
        this.toolPipeline = toolPipeline;
        this.skillExpander = skillExpander;
    }

    public Toolkit create() {
        Toolkit toolkit = new Toolkit();

        // Read-only discovery stays available before a skill is activated. The human-input tool
        // is always registered because preflight clarifications are part of the turn protocol.
        sessionTools.tools().forEach(tool -> register(toolkit, tool));
        structuredTools.tools().forEach(tool -> register(toolkit, tool));
        register(toolkit, assetGroupTools.tool());
        register(toolkit, interactionTools.storyboardCommitTool());
        register(toolkit, interactionTools.tool());
        register(toolkit, workflowTools.tool());
        register(toolkit, videoMergeTools.tool());
        documentTools.tools().forEach(tool -> register(toolkit, tool));
        if (sandboxTools != null) {
            sandboxTools.tools().forEach(tool -> register(toolkit, tool));
        }
        if (webTools != null) {
            webTools.tools().forEach(tool -> register(toolkit, tool));
        }
        creativeTools.tools().forEach(tool -> register(toolkit, tool));
        storyboardTools.tools().forEach(tool -> register(toolkit, tool));
        toolkit.createSkillToolGroup(
                CANVAS_DESIGN_TOOLS, "Canvas graph design tools", false, "canvas-design");
        toolkit.createSkillToolGroup(
                CANVAS_RUN_TOOLS, "Canvas graph execution tools", false, "canvas-run");
        activeToolkit = toolkit;
        registerCanvasTools(toolkit);

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

    /**
     * Canvas Bridge may connect after this toolkit has already been attached to
     * the long-lived HarnessAgent. Refresh the mutable registry in place.
     */
    public synchronized void refreshCanvasTools() {
        Toolkit toolkit = activeToolkit;
        if (toolkit != null) registerCanvasTools(toolkit);
    }

    Toolkit activeToolkit() {
        return activeToolkit;
    }

    private void registerCanvasTools(Toolkit toolkit) {
        ToolCallback[] callbacks = skillExpander.toolCallbacksByPrefix("canvas_");
        if (callbacks == null) callbacks = new ToolCallback[0];
        Set<String> desired = Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().name())
                .filter(AgentScopeToolkitFactory::isCanvasRunTool)
                .collect(Collectors.toSet());
        desired.add(AgentScopeCanvasRunTools.CREATE_CANVAS_RUN);
        for (String existing : List.copyOf(toolkit.getToolNames())) {
            if (existing.startsWith("canvas_") && !desired.contains(existing)) {
                toolkit.removeTool(existing);
            }
        }
        for (ToolCallback canvasTool : callbacks) {
            String name = canvasTool.getToolDefinition().name();
            if (!isCanvasRunTool(name)) continue;
            if (toolkit.getTool(name) != null) toolkit.removeTool(name);
            if (name.startsWith("canvas_graph_")) {
                registerCanvasTool(toolkit, canvasTool, CANVAS_DESIGN_TOOLS);
                registerCanvasTool(toolkit, canvasTool, CANVAS_RUN_TOOLS);
            } else {
                registerCanvasTool(toolkit, canvasTool, CANVAS_RUN_TOOLS);
            }
        }
        AgentScopeTool createRunTool = canvasRunTools.tool();
        registerToGroup(toolkit, toolPipeline.wrap(createRunTool), CANVAS_DESIGN_TOOLS);
        registerToGroup(toolkit, toolPipeline.wrap(createRunTool), CANVAS_RUN_TOOLS);
    }

    private static boolean isCanvasRunTool(String name) {
        return name != null && (name.startsWith("canvas_graph_")
                || canvasRunTool(name));
    }

    private void registerCanvasTool(Toolkit toolkit, ToolCallback callback, String group) {
        if (callback.getToolDefinition().name().startsWith("canvas_graph_")) {
            registerToGroup(toolkit,
                    toolPipeline.wrapGeneric(new CanvasRunToolAdapter(callback, canvasRuns)),
                    group);
            return;
        }
        registerToGroup(toolkit,
                toolPipeline.wrapGeneric(new SpringAiToolAdapter(callback)), group);
    }

    private static boolean canvasRunTool(String name) {
        return Set.of(
                "canvas_execute_node", "canvas_execute_graph",
                "canvas_get_execution", "canvas_cancel_execution")
                .contains(name);
    }

    private static void registerToGroup(Toolkit toolkit, io.agentscope.core.tool.AgentTool tool,
                                        String group) {
        toolkit.registration().agentTool(tool).group(group).apply();
    }

    private void register(Toolkit toolkit, AgentScopeTool tool) {
        toolkit.registerAgentTool(toolPipeline.wrap(tool));
    }

    private void register(Toolkit toolkit, io.agentscope.core.tool.AgentTool tool) {
        toolkit.registerAgentTool(tool);
    }
}
