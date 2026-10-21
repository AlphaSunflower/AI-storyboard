package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.skill.SkillExpander;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

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

    /** 技能组名统一由 {@link ToolCatalog} 声明，这里只保留对外兼容别名。 */
    public static final String IMAGE_TOOLS = ToolCatalog.IMAGE_TOOLS;
    public static final String VIDEO_TOOLS = ToolCatalog.VIDEO_TOOLS;
    public static final String CANVAS_DESIGN_TOOLS = ToolCatalog.CANVAS_DESIGN_TOOLS;
    public static final String CANVAS_RUN_TOOLS = ToolCatalog.CANVAS_RUN_TOOLS;
    public static final String STORYBOARD_TOOLS = ToolCatalog.STORYBOARD_TOOLS;

    /**
     * 画布能力探测标记：这两个工具齐全才算画布链路真的可用。
     *
     * <p>判定必须落在 Toolkit 注册表上，而不是 {@link SkillExpander} 的工具索引：
     * 索引是只增不减的「发现记录」，注册表才是模型调用时真正解析的工具集合。
     * 两者错位时会向模型声明「画布可用」，模型随后每个调用都拿到
     * {@code Tool not found}（2026-10-15 实测事故）。
     */
    private static final List<String> CANVAS_MARKERS = ToolCatalog.CANVAS_PROBE_TOOLS;

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
    @Autowired(required = false)
    private AgentScopeOrchestrationTools orchestrationTools;
    @Autowired(required = false)
    private AgentScopeMemoryTools memoryTools;
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
        java.time.Duration toolTimeout = properties.getToolExecutionTimeout();
        long required = properties.getSubagentMaxDurationMs()
                * (properties.getSubagentMaxRepairRounds() + 1L) + 60_000;
        if (required > 0 && toolTimeout.toMillis() < required) {
            toolTimeout = java.time.Duration.ofMillis(required);
            log.warn("[Subagent] tool-execution-timeout={}s 不足以覆盖初跑+修复两段预算，"
                            + "已提升到 {}s",
                    properties.getToolExecutionTimeout().toSeconds(), toolTimeout.toSeconds());
        }
        Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
                .executionConfig(ExecutionConfig.builder()
                        .timeout(toolTimeout)
                        .maxAttempts(1)
                        .build())
                .build());

        // 1) 技能组由 ToolCatalog 统一声明（名称/描述/技能/初始激活状态）。
        ToolCatalog.groups().values().forEach(group -> toolkit.createSkillToolGroup(
                group.name(), group.description(), group.activeByDefault(), group.skill()));

        // 2) 收集当前可用的自研工具，按目录登记的策略/组注册。
        Map<String, AgentScopeTool> available = new LinkedHashMap<>();
        collect(available, sessionTools.tools());
        collect(available, structuredTools.tools());
        collect(available, documentTools.tools());
        collect(available, creativeTools.tools());
        if (memoryTools != null) collect(available, memoryTools.tools());
        collect(available, storyboardTools.tools());
        if (orchestrationTools != null) collect(available, orchestrationTools.tools());
        if (sandboxTools != null) collect(available, sandboxTools.tools());
        if (webTools != null) collect(available, webTools.tools());
        add(available, assetGroupTools.tool());
        add(available, interactionTools.tool());
        add(available, interactionTools.storyboardCommitTool());
        add(available, workflowTools.tool());
        add(available, imageTools.tool());
        add(available, imageTools.batchTool());
        add(available, videoTools.tool());
        add(available, journeyTools.tool());
        add(available, videoMergeTools.tool());

        Set<String> registered = new java.util.HashSet<>();
        for (ToolCatalog.Spec spec : ToolCatalog.specs()) {
            AgentScopeTool tool = available.get(spec.name());
            if (tool == null) continue;
            if (spec.groups().isEmpty()) {
                // 无组的工具必须在技能激活前就可见（读取类/交互类）。
                register(toolkit, tool);
            } else {
                for (String group : spec.groups()) {
                    registerToGroup(toolkit, toolPipeline.wrap(tool), group);
                }
            }
            registered.add(spec.name());
        }
        // 目录漏登记时按常驻兜底并告警：生产不静默丢工具，回归测试会锁住目录完整性。
        for (Map.Entry<String, AgentScopeTool> entry : available.entrySet()) {
            if (registered.contains(entry.getKey())) continue;
            log.warn("[ToolCatalog] 工具未登记，按常驻兜底: {}", entry.getKey());
            register(toolkit, entry.getValue());
        }

        // refineImage 来自 Spring AI MCP 回调，不在 AgentScopeTool 集合里。
        ToolCallback[] imageCallbacks = skillExpander.toolCallbacks(List.of("refineImage"));
        if (imageCallbacks.length > 0) {
            registerToGroup(toolkit,
                    toolPipeline.wrapGeneric(new SpringAiToolAdapter(imageCallbacks[0])),
                    IMAGE_TOOLS);
        }

        activeToolkit = toolkit;
        registerCanvasTools(toolkit);
        log.info("[Canvas] Toolkit 启动注册画布工具 {} 个{}",
                canvasToolNames().size(),
                canvasToolNames().isEmpty() ? "（画布 MCP 未就绪，连接看门狗会继续补齐）" : "");
        return toolkit;
    }

    private static void collect(Map<String, AgentScopeTool> out, List<AgentScopeTool> tools) {
        if (tools == null) return;
        for (AgentScopeTool tool : tools) add(out, tool);
    }

    private static void add(Map<String, AgentScopeTool> out, AgentScopeTool tool) {
        if (tool != null && tool.getName() != null && !tool.getName().isBlank()) {
            out.put(tool.getName(), tool);
        }
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
                // 分组属于「结构」而不是「执行」：设计阶段搭完图就要分组，
                // 因此它和 canvas_graph_* 一样同时进设计组与执行组。
                if (name.startsWith("canvas_graph_") || "canvas_group_nodes".equals(name)) {
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
                "canvas_get_execution", "canvas_cancel_execution",
                // 分组：执行入口只存在于分组，设计阶段与执行阶段都要能用
                "canvas_group_nodes")
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
