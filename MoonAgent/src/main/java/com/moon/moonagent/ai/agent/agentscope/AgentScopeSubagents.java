package com.moon.moonagent.ai.agent.agentscope;

import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;

import java.util.List;

final class AgentScopeSubagents {

    private AgentScopeSubagents() {
    }

    static List<SubagentDeclaration> readOnlyExecutors() {
        return readOnlyExecutors("", "");
    }

    /**
     * 只读创作子智能体清单。
     *
     * <p>{@code visual-critic} 已下线：它对每次生成结果都额外发起一次视觉模型评审，
     * 既增加成本，又可能在用户已经满意首图时触发多余的修复重生成。
     * 生成结果不再由智能体自动诊断，是否需要改图完全由用户决定。
     */
    static List<SubagentDeclaration> readOnlyExecutors(String promptModel,
                                                       String visionModel) {
        return List.of(
                declaration(
                        "creative-planner",
                        "Breaks complex creative goals into verifiable stages, dependencies and "
                                + "acceptance criteria without creating media.",
                        """
                        You are a read-only creative planner.
                        Produce a compact execution plan with goals, dependencies,
                        selected tools, model roles, budgets and acceptance checks.
                        Never create media or mutate project data.
                        Return JSON with summary, assetIds and unresolved.
                        """,
                        List.of(
                                AgentScopeCreativeTools.GET_CONTEXT,
                                AgentScopeCreativeTools.EXTRACT_CONSTRAINTS,
                                AgentScopeStructuredTools.BUILD_MEDIA_SPEC),
                        promptModel, 8, true),
                declaration(
                        "prompt-director",
                        "Refines image or video prompts and generation parameters without "
                                + "creating media.",
                        """
                        You are a read-only visual prompt director.
                        Return a concise JSON object with summary, assetIds and unresolved.
                        Never create or edit media directly.
                        """,
                        List.of(
                                AgentScopeCreativeTools.GET_CONTEXT,
                                AgentScopeStructuredTools.BUILD_MEDIA_SPEC),
                        promptModel, 6, false),
                declaration(
                        "reference-curator",
                        "Finds only the prior session assets relevant to a referential request.",
                        """
                        You locate relevant prior conversation assets.
                        Return a concise JSON object with summary, assetIds and unresolved.
                        Never create media and never assume an unrelated asset is relevant.
                        """,
                        List.of(
                                AgentScopeSessionTools.LIST_SESSION_ASSETS,
                                AgentScopeSessionTools.RESOLVE_REFERENCE_ASSETS,
                                AgentScopeAssetGroupTools.LIST_ASSET_GROUPS,
                                AgentScopeCreativeTools.GET_CONTEXT),
                        promptModel, 6, false),
                declaration(
                        "continuity-editor",
                        "Checks cross-shot identity, style, material and temporal continuity.",
                        """
                        You are a read-only continuity editor.
                        Compare approved and new shots for subject, style, color, lighting,
                        spatial and temporal continuity. Report concrete drift only.
                        Return JSON with summary, assetIds and unresolved.
                        Never create media.
                        """,
                        List.of(
                                AgentScopeCreativeTools.GET_CONTEXT,
                                AgentScopeCreativeTools.LIST_CRITIQUES,
                                AgentScopeSessionTools.LIST_SESSION_ASSETS,
                                AgentScopeCreativeTools.GET_TIMELINE),
                        visionModel, 8, true));
    }

    private static SubagentDeclaration declaration(
            String name, String description, String prompt, List<String> tools,
            String model, int steps, boolean persistSession) {
        SubagentDeclaration.Builder builder = SubagentDeclaration.builder()
                .name(name)
                .description(description)
                .workspaceMode(WorkspaceMode.SHARED)
                .inlineAgentsBody(prompt)
                .mode(SubagentDeclaration.Mode.SUBAGENT)
                .steps(steps)
                .persistSession(persistSession)
                .inheritParentPermissions(true)
                .tools(tools);
        if (model != null && !model.isBlank()) builder.model(model);
        return builder.build();
    }
}
