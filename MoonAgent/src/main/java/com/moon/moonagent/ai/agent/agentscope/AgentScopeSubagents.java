package com.moon.moonagent.ai.agent.agentscope;

import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;

import java.util.List;

final class AgentScopeSubagents {

    private AgentScopeSubagents() {
    }

    static List<SubagentDeclaration> readOnlyExecutors() {
        return List.of(
                declaration(
                        "visual-prompt-director",
                        "Refines image or video prompts and generation parameters without "
                                + "creating media.",
                        """
                        You are a read-only visual prompt director.
                        Return a concise JSON object with summary, assetIds and unresolved.
                        Never create or edit media directly.
                        """,
                        List.of(AgentScopeStructuredTools.BUILD_MEDIA_SPEC)),
                declaration(
                        "reference-locator",
                        "Finds only the prior session assets relevant to a referential request.",
                        """
                        You locate relevant prior conversation assets.
                        Return a concise JSON object with summary, assetIds and unresolved.
                        Never create media and never assume an unrelated asset is relevant.
                        """,
                        List.of(
                                AgentScopeSessionTools.LIST_SESSION_ASSETS,
                                AgentScopeSessionTools.RESOLVE_REFERENCE_ASSETS,
                                AgentScopeAssetGroupTools.LIST_ASSET_GROUPS)),
                declaration(
                        "media-risk-reviewer",
                        "Reviews ambiguous or unusually expensive media requests.",
                        """
                        You review AIGC requests for ambiguity, cost and safety.
                        Return a concise JSON object with summary, assetIds and unresolved.
                        Never create media.
                        """,
                        List.of(AgentScopeStructuredTools.ASSESS_GENERATION_RISK)));
    }

    private static SubagentDeclaration declaration(
            String name, String description, String prompt, List<String> tools) {
        return SubagentDeclaration.builder()
                .name(name)
                .description(description)
                .workspaceMode(WorkspaceMode.SHARED)
                .inlineAgentsBody(prompt)
                .mode(SubagentDeclaration.Mode.SUBAGENT)
                .steps(6)
                .persistSession(false)
                .inheritParentPermissions(true)
                .tools(tools)
                .build();
    }
}
