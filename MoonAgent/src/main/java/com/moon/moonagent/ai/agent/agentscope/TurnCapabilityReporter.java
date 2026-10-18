package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.creative.CreativeModelRoles;
import com.moon.moonagent.ai.agent.sandbox.MoonSandboxClient;
import com.moon.moonagent.ai.agent.skill.ToolAvailability;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 每轮把「真实可用的能力」声明进系统提示词的 {@code <capabilities>} 注入块。
 *
 * <p>存在意义：Skill 工具组（画布 / 图片 / 视频）只在 Skill 激活或 MCP 连接成功后注册，
 * 画布工具还依赖 Canvas MCP 服务。提示词里静态点名这些工具，会在能力缺席时制造
 * 「模型承诺一个做不到的动作」。因此把可用性与适用子智能体改为运行时事实，
 * 由本组件从 {@link AgentScopeToolkitFactory} 的 <b>Toolkit 注册表</b>与配置开关推导，
 * 提示词只声明「以注入块为准」。
 *
 * <p>判定必须落在 Toolkit 注册表上：{@link com.moon.moonagent.ai.agent.skill.SkillExpander}
 * 是只增不减的发现记录，它在注册表为空时仍会宣称画布可用，模型据此激活空工具组，
 * 然后每个调用都拿到 {@code Tool not found}（2026-10-15 实测事故）。注册表不存在时
 * （AgentScope 未启用/单测）才退回发现记录。
 */
@Component
@RequiredArgsConstructor
public class TurnCapabilityReporter {

    /** 画布设计/执行能力的探测标记：与 Toolkit 共用同一份口径。 */
    private static final List<String> CANVAS_MARKERS = ToolCatalog.CANVAS_PROBE_TOOLS;

    private final ToolAvailability tools;
    private final AgentScopeProperties properties;
    private final CreativeModelRoles modelRoles;
    private final ObjectProvider<MoonSandboxClient> sandboxClient;
    private final ObjectProvider<AgentScopeWebTools> webTools;
    private final ObjectProvider<AgentScopeDocumentTools> documentTools;
    private final ObjectProvider<AgentScopeStoryboardTools> storyboardTools;
    private final ObjectProvider<AgentScopeToolkitFactory> toolkitFactory;

    /**
     * 生成能力声明块；无任何可报道内容时返回空串。
     */
    public String capabilityReport() {
        List<String> lines = new ArrayList<>();
        List<String> available = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();

        classify(hasCanvasTools(), "画布设计与执行", available, unavailable);
        classify(hasImageTools(), "图片生成与参考图编辑", available, unavailable);
        classify(hasVideoTools(), "视频生成", available, unavailable);
        classify(sandboxReady(), "Python 沙箱脚本执行与 output/ 产物交付",
                available, unavailable);
        classify(documentReady(), "文档解析", available, unavailable);
        classify(storyboardReady(), "项目分镜读取（含按 scene_id / 分镜号读单镜）",
                available, unavailable);
        classify(webReady(), "网页检索", available, unavailable);

        if (!available.isEmpty()) {
            lines.add("此轮可用的能力：" + String.join("；", available) + "。");
        }
        if (!unavailable.isEmpty()) {
            lines.add("此轮不可用的能力：" + String.join("；", unavailable)
                    + "。不要提议、承诺或假装调用这些能力；用户需要时说明当前不可用。");
        }
        String subagents = subagentReport();
        if (!subagents.isBlank()) lines.add(subagents);
        return String.join("\n", lines);
    }

    /**
     * Toolkit 注册表里的工具名；运行时尚未创建 Toolkit（或 AgentScope 未启用）时返回
     * {@code null}，调用方据此退回发现记录。
     */
    private Set<String> registeredToolNames() {
        AgentScopeToolkitFactory factory = toolkitFactory.getIfAvailable();
        if (factory == null) return null;
        Set<String> names = factory.registeredToolNames();
        return names == null ? null : names;
    }

    /**
     * 画布链路是否连通，只以 Toolkit 注册表里的标记为准。
     *
     * <p>{@code canvas_create_run} 不参与判定：它由 {@link AgentScopeToolkitFactory} 无条件注册进
     * Toolkit 的 skill 工具组，把它当判据会让画布恒判可用（它的存在不代表画布 MCP 连上了）。
     */
    private boolean hasCanvasTools() {
        Set<String> registry = registeredToolNames();
        if (registry == null) return CANVAS_MARKERS.stream().allMatch(tools::hasTool);
        return CANVAS_MARKERS.stream().allMatch(registry::contains);
    }

    private boolean hasImageTools() {
        Set<String> registry = registeredToolNames();
        if (registry == null) {
            return tools.hasTool(AgentScopeImageTools.GENERATE_IMAGE)
                    || tools.hasTool("refineImage");
        }
        return registry.contains(AgentScopeImageTools.GENERATE_IMAGE)
                || registry.contains("refineImage");
    }

    private boolean hasVideoTools() {
        Set<String> registry = registeredToolNames();
        if (registry == null) return tools.hasTool(AgentScopeVideoTools.GENERATE_VIDEO);
        return registry.contains(AgentScopeVideoTools.GENERATE_VIDEO);
    }

    private boolean sandboxReady() {
        MoonSandboxClient client = sandboxClient.getIfAvailable();
        return client != null && client.enabled();
    }

    /** 文档解析工具 bean 恒存在，但受配置开关控制是否真的注册 {@code read_document}。 */
    private boolean documentReady() {
        AgentScopeDocumentTools documents = documentTools.getIfAvailable();
        return documents != null && documents.enabled();
    }

    /**
     * 项目分镜读取能力（{@code list_project_storyboard} / {@code read_project_scene}）：
     * 以 Toolkit 注册表为准，注册表尚未建立时退回工具 bean 是否装配。
     */
    private boolean storyboardReady() {
        Set<String> registry = registeredToolNames();
        if (registry == null) {
            return tools.hasTool(AgentScopeStoryboardTools.READ_PROJECT_SCENE)
                    || tools.hasTool(AgentScopeStoryboardTools.LIST_PROJECT_STORYBOARD);
        }
        return registry.contains(AgentScopeStoryboardTools.READ_PROJECT_SCENE)
                || registry.contains(AgentScopeStoryboardTools.LIST_PROJECT_STORYBOARD);
    }

    /**
     * 网页检索工具 bean 恒存在（开关只关 {@code @ConditionalOnProperty} 的 enabled），
     * 但 {@code AgentScopeWebTools.tools()} 在没有搜索 key 时返回空列表、不注册任何工具。
     * 只看 bean 存在会把「没配置 key」误报成可用，模型随后就会承诺一个调不出来的能力。
     */
    private boolean webReady() {
        AgentScopeWebTools web = webTools.getIfAvailable();
        return web != null && web.configured();
    }

    /**
     * 子智能体清单直接来自注册表声明，避免在提示词里维护第二份名单。
     */
    private String subagentReport() {
        if (!properties.isSubagentsEnabled()) return "";
        List<String> entries = new ArrayList<>();
        for (SubagentDeclaration declaration : AgentScopeSubagents.readOnlyExecutors(
                modelRoles.resolveOrNull("creative.prompt"),
                modelRoles.resolveOrNull("vision.qa"))) {
            String description = declaration.getDescription();
            entries.add(declaration.getName() + "："
                    + (description == null ? "" : description.trim()));
        }
        if (entries.isEmpty()) return "";
        return "可用子智能体（只读，不创建媒体）：\n- " + String.join("\n- ", entries);
    }

    private static void classify(boolean ready, String label,
                                 List<String> available, List<String> unavailable) {
        (ready ? available : unavailable).add(label);
    }
}
