package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.skill.SkillExpander;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    public static final String STORYBOARD_TOOLS = "storyboard-tools";

    /**
     * 画布能力探测标记：这两个工具齐全才算画布链路真的可用。
     *
     * <p>判定必须落在 Toolkit 注册表上，而不是 {@link SkillExpander} 的工具索引：
     * 索引是只增不减的「发现记录」，注册表才是模型调用时真正解析的工具集合。
     * 两者错位时会向模型声明「画布可用」，模型随后每个调用都拿到
     * {@code Tool not found}（2026-10-15 实测事故）。
     */
    private static final List<String> CANVAS_MARKERS = List.of(
            "canvas_graph_get_state", "canvas_graph_layout_graph");

    private static final Logger log = LoggerFactory.getLogger(AgentScopeToolkitFactory.class);

    private final AgentScopeSessionTools sessionTools;
    private final AgentScopeInteractionTools interactionTools;
    private final AgentScopeStructuredTools structuredTools;
    private final AgentScopeAssetGroupTools assetGroupTools;
    private final AgentScopeImageTools imageTools;
    private final AgentScopeVideoTools videoTools;
    private final AgentScopeJourneyTools journeyTools;
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
    private final AgentScopeProperties properties;
    private volatile Toolkit activeToolkit;

    public AgentScopeToolkitFactory(AgentScopeSessionTools sessionTools,
                                    AgentScopeInteractionTools interactionTools,
                                    AgentScopeStructuredTools structuredTools,
                                    AgentScopeAssetGroupTools assetGroupTools,
                                    AgentScopeImageTools imageTools,
                                    AgentScopeVideoTools videoTools,
                                    AgentScopeJourneyTools journeyTools,
                                    AgentScopeStoryboardTools storyboardTools,
                                    AgentScopeWorkflowTools workflowTools,
                                    AgentScopeVideoMergeTools videoMergeTools,
                                    AgentScopeCreativeTools creativeTools,
                                    AgentScopeCanvasTools canvasTools,
                                    AgentScopeCanvasRunTools canvasRunTools,
                                    AgentScopeDocumentTools documentTools,
                                    AgentCanvasRunService canvasRuns,
                                    AgentScopeToolPipeline toolPipeline,
                                    SkillExpander skillExpander,
                                    AgentScopeProperties properties) {
        this.sessionTools = sessionTools;
        this.interactionTools = interactionTools;
        this.structuredTools = structuredTools;
        this.assetGroupTools = assetGroupTools;
        this.imageTools = imageTools;
        this.videoTools = videoTools;
        this.journeyTools = journeyTools;
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
        this.properties = properties;
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
        documentTools.tools().forEach(tool -> register(toolkit, tool));
        if (sandboxTools != null) {
            sandboxTools.tools().forEach(tool -> register(toolkit, tool));
        }
        if (webTools != null) {
            webTools.tools().forEach(tool -> register(toolkit, tool));
        }
        // 创作记忆：**读取**类常驻（技能激活前就要能读），**写入/时间线**类按技能门控。
        //
        // 依据 TurnContextMiddleware:191-200——每轮已自动注入 <project-creative-context>
        // （目标/方向/风格/硬软约束/参考/被拒方向/反馈/本轮模式）与约束账本，
        // 所以 get_creative_context / extract_creative_constraints 属于"可在激活前调用"的读取面；
        // 而 update_creative_context / save|get_timeline_manifest 只在具体创作任务里才有意义。
        Set<String> gatedCreativeNames = Set.of(
                AgentScopeCreativeTools.UPDATE_CONTEXT,
                AgentScopeCreativeTools.SAVE_TIMELINE,
                AgentScopeCreativeTools.GET_TIMELINE);
        List<AgentScopeTool> gatedCreativeTools = new ArrayList<>();
        creativeTools.tools().forEach(tool -> {
            if (gatedCreativeNames.contains(tool.getName())) {
                gatedCreativeTools.add(tool);
            } else {
                register(toolkit, tool);
            }
        });
        // 分镜 16 个工具改为技能门控（2026-10-17）：它们此前无条件注册，
        // 使每一轮（哪怕只是闲聊）都带着 16 个分镜 schema，占常驻工具面 40%。
        // storyboard 技能的 allowed-tools 已声明这些工具，激活技能即可见。
        toolkit.createSkillToolGroup(
                STORYBOARD_TOOLS, "Storyboard production tools", false, "storyboard");
        storyboardTools.tools().forEach(tool -> toolkit.registration()
                .agentTool(toolPipeline.wrap(tool))
                .group(STORYBOARD_TOOLS).apply());
        // 成片合并只服务于分镜链路，与分镜同组，避免常驻。
        toolkit.registration()
                .agentTool(toolPipeline.wrap(videoMergeTools.tool()))
                .group(STORYBOARD_TOOLS).apply();
        toolkit.createSkillToolGroup(
                CANVAS_DESIGN_TOOLS, "Canvas graph design tools", false, "canvas-design");
        toolkit.createSkillToolGroup(
                CANVAS_RUN_TOOLS, "Canvas graph execution tools", false, "canvas-run");
        activeToolkit = toolkit;
        registerCanvasTools(toolkit);
        log.info("[Canvas] Toolkit 启动注册画布工具 {} 个{}",
                canvasToolNames().size(),
                canvasToolNames().isEmpty() ? "（画布 MCP 未就绪，连接看门狗会继续补齐）" : "");

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
        // 复合旅程与视频同组：用户提到「生图然后视频 / 超过 15 秒走分镜」时由它一次编排完成。
        toolkit.registration()
                .agentTool(toolPipeline.wrap(journeyTools.tool()))
                .group(VIDEO_TOOLS).apply();

        // 门控的创作写入/时间线工具：必须在 image/video 组创建之后再注册。
        // 一个工具可同时属于多个技能组（画布工具就是 design+run 双组），
        // 所以按「哪些技能真的需要它」逐个挂，而不是塞进单一组。
        Map<String, List<String>> creativeGroupByTool = new LinkedHashMap<>();
        creativeGroupByTool.put(AgentScopeCreativeTools.UPDATE_CONTEXT,
                List.of(STORYBOARD_TOOLS, IMAGE_TOOLS, VIDEO_TOOLS, CANVAS_DESIGN_TOOLS));
        creativeGroupByTool.put(AgentScopeCreativeTools.SAVE_TIMELINE,
                List.of(STORYBOARD_TOOLS, VIDEO_TOOLS));
        creativeGroupByTool.put(AgentScopeCreativeTools.GET_TIMELINE,
                List.of(STORYBOARD_TOOLS, VIDEO_TOOLS));
        for (AgentScopeTool tool : gatedCreativeTools) {
            for (String group : creativeGroupByTool.getOrDefault(tool.getName(), List.of())) {
                toolkit.registration()
                        .agentTool(toolPipeline.wrap(tool))
                        .group(group).apply();
            }
        }
        return toolkit;
    }

    /**
     * 绑定 agent 真正使用的 Toolkit。
     *
     * <p>{@code HarnessAgent.build()} 会对传入的 Toolkit 做**构建期深拷贝**
     * （{@code Toolkit.copy()}：注册表与工具组都是隔离副本），此后 agent 只认自己那一份。
     * 画布工具是 MCP 连上后才注册的延迟工具，必须写在 agent 的实际注册表上；否则
     * {@code refreshCanvasTools()} 全部写进原始对象，模型看到的永远是空工具组，
     * 每个画布调用都返回 {@code Tool not found}（2026-10-15 实测事故）。
     */
    public synchronized void bindAgentToolkit(Toolkit agentToolkit) {
        if (agentToolkit == null) return;
        activeToolkit = agentToolkit;
        registerCanvasTools(agentToolkit);
        guardPlanTools(agentToolkit);
        log.info("[Canvas] Toolkit 已绑定 agent 实例，画布工具 {} 个{}",
                canvasToolNames().size(),
                canvasToolNames().isEmpty() ? "（画布 MCP 未就绪，连接看门狗会继续补齐）" : "");
    }

    /**
     * build 期一次性把计划/待办工具换成「每轮作用域守卫」。
     *
     * <p>{@code plan_enter} / {@code plan_write} / {@code plan_exit} / {@code todo_write} 由
     * harness/core 在 build 期注册且**不带 group**，而 ungrouped 工具永远对模型可见——按轮
     * 摘除会踩到共用 Toolkit 的并发会话，因此只能替换成自己判断轮次的作用域包装。
     *
     * <p>幂等：{@code bindAgentToolkit} 可能被多次调用（实测启动日志里出现两次绑定），
     * 已经是 {@link CanvasPlanToolGuard} 的一律跳过，避免套娃。
     */
    private void guardPlanTools(Toolkit toolkit) {
        for (String name : CanvasPlanToolGuard.GUARDED_TOOL_NAMES) {
            AgentTool original = toolkit.getTool(name);
            if (original == null) continue;
            if (original instanceof CanvasPlanToolGuard) continue;
            toolkit.removeTool(name);
            toolkit.registerAgentTool(new CanvasPlanToolGuard(original, canvasRuns));
            log.info("[Canvas] 计划/待办工具已加画布轮守卫: {}", name);
        }
    }

    /**
     * 画布工具延迟注册：Canvas MCP 可能在 Toolkit 已挂到常驻 HarnessAgent 之后才连上。
     *
     * <p>本方法就地补齐注册表，是 {@code CanvasMcpConnectionManager} 看门狗唯一的修复入口；
     * 探测为空时只保留现状，不摘除已注册的画布工具。
     */
    public synchronized void refreshCanvasTools() {
        Toolkit toolkit = activeToolkit;
        if (toolkit != null) registerCanvasTools(toolkit);
    }

    Toolkit activeToolkit() {
        return activeToolkit;
    }

    /**
     * 当前 Toolkit 注册表里的全部工具名——能力判定与就绪探测的唯一真源。
     *
     * <p>没有任何工具时返回空集合，Toolkit 尚未创建时也返回空集合（调用方据此判为不可用）。
     */
    public Set<String> registeredToolNames() {
        Toolkit toolkit = activeToolkit;
        return toolkit == null ? Set.of() : Set.copyOf(toolkit.getToolNames());
    }

    /** 指定工具是否真的注册在 Toolkit 里（模型调用时能解析到）。 */
    public boolean hasTool(String name) {
        Toolkit toolkit = activeToolkit;
        return toolkit != null && name != null && toolkit.getTool(name) != null;
    }

    /** 画布链路是否真的可用：以 Toolkit 注册表里的探测标记为准。 */
    public boolean hasCanvasTools() {
        Set<String> names = registeredToolNames();
        return CANVAS_MARKERS.stream().allMatch(names::contains);
    }

    /** Toolkit 里已注册的画布工具名（诊断与能力声明共用）。 */
    public Set<String> canvasToolNames() {
        return registeredToolNames().stream()
                .filter(AgentScopeToolkitFactory::isCanvasRunTool)
                .collect(Collectors.toUnmodifiableSet());
    }

    private void registerCanvasTools(Toolkit toolkit) {
        ToolCallback[] callbacks = skillExpander.toolCallbacksByPrefix("canvas_");
        if (callbacks == null) callbacks = new ToolCallback[0];
        Set<String> desired = Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().name())
                .filter(AgentScopeToolkitFactory::isCanvasRunTool)
                .collect(Collectors.toSet());
        desired.add(AgentScopeCanvasRunTools.CREATE_CANVAS_RUN);

        // 空探测绝不删除：画布 MCP 半途不可用 / 客户端暂时取不到工具列表时，
        // 一旦按空集合收敛就会把已经注册好的画布工具全部摘掉，而 SkillExpander 的
        // 发现记录还在（只增不减），运行时于是继续宣称「画布可用」，模型每个调用
        // 都拿到 Tool not found。注册表只允许被「非空且更新」的探测替换。
        boolean probeEmpty = Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().name())
                .noneMatch(name -> name != null && name.startsWith("canvas_graph_"));
        if (probeEmpty) {
            if (!canvasToolNames().isEmpty()) {
                log.debug("[Canvas] 画布 MCP 工具列表探测为空，保留 Toolkit 中已注册的 {} 个画布工具，不做摘除",
                        canvasToolNames().size());
            } else {
                log.debug("[Canvas] 画布 MCP 工具未注册（探测为空），等待 CanvasMcpConnectionManager 补齐");
            }
        } else {
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
        }
        AgentScopeTool createRunTool = canvasRunTools.tool();
        registerToGroup(toolkit, toolPipeline.wrap(createRunTool), CANVAS_DESIGN_TOOLS);
        registerToGroup(toolkit, toolPipeline.wrap(createRunTool), CANVAS_RUN_TOOLS);
        log.debug("[Canvas] Toolkit 画布工具数={}（canvas_create_run 恒注册）", canvasToolNames().size());
    }

    private static boolean isCanvasRunTool(String name) {
        return name != null && (name.startsWith("canvas_graph_")
                || canvasRunTool(name));
    }

    private void registerCanvasTool(Toolkit toolkit, ToolCallback callback, String group) {
        // 画布工具（canvas_graph_* 与服务端 canvas_execute_*）统一走 run 适配器：
        // 由它注入 userId/projectId/canvasId/runId/operationKey，并提供操作幂等与进度。
        // 注入型参数默认从模型可见 schema 中隐藏（开关：canvas-schema-hide-injected-params）。
        registerToGroup(toolkit,
                toolPipeline.wrapGeneric(new CanvasRunToolAdapter(
                        callback, canvasRuns, properties.isCanvasSchemaHideInjectedParams())),
                group);
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
