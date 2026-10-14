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

/**
 * 每轮把「真实可用的能力」声明进系统提示词的 {@code <capabilities>} 注入块。
 *
 * <p>存在意义：Skill 工具组（画布 / 图片 / 视频）只在 Skill 激活或 MCP 连接成功后注册，
 * 画布工具还依赖本地 Bridge 进程。提示词里静态点名这些工具，会在能力缺席时制造
 * 「模型承诺一个做不到的动作」。因此把可用性与适用子智能体改为运行时事实，
 * 由本组件从 {@link com.moon.moonagent.ai.agent.skill.SkillExpander} 注册表与配置开关推导，
 * 提示词只声明「以注入块为准」。
 */
@Component
@RequiredArgsConstructor
public class TurnCapabilityReporter {

    /** 画布设计/执行能力的探测标记：任一缺失即视为该能力组不可用。 */
    private static final List<String> CANVAS_MARKERS = List.of(
            "canvas_graph_get_state",
            "canvas_graph_layout_graph");

    private final ToolAvailability tools;
    private final AgentScopeProperties properties;
    private final CreativeModelRoles modelRoles;
    private final ObjectProvider<MoonSandboxClient> sandboxClient;
    private final ObjectProvider<AgentScopeWebTools> webTools;
    private final ObjectProvider<AgentScopeDocumentTools> documentTools;

    /**
     * 生成能力声明块；无任何可报道内容时返回空串。
     */
    public String capabilityReport() {
        List<String> lines = new ArrayList<>();
        List<String> available = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();

        classify(hasCanvasTools(), "画布设计与执行", available, unavailable);
        classify(hasImageTools(), "图片生成与参考图编辑", available, unavailable);
        classify(tools.hasTool(AgentScopeVideoTools.GENERATE_VIDEO),
                "视频生成", available, unavailable);
        classify(sandboxReady(), "Python 沙箱脚本执行与 output/ 产物交付",
                available, unavailable);
        classify(documentReady(), "文档解析", available, unavailable);
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
     * 画布链路是否连通，只以 Canvas MCP 服务端提供的标记为准。
     *
     * <p>{@code canvas_create_run} 不参与判定：它由 {@link AgentScopeToolkitFactory} 无条件注册进
     * Toolkit 的 skill 工具组，不会出现在 {@link ToolAvailability} 里，把它当判据会让画布恒判不可用。
     */
    private boolean hasCanvasTools() {
        return CANVAS_MARKERS.stream().allMatch(tools::hasTool);
    }

    private boolean hasImageTools() {
        return tools.hasTool(AgentScopeImageTools.GENERATE_IMAGE)
                || tools.hasTool("refineImage");
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
                modelRoles.resolveOrNull("creative.critic"),
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
