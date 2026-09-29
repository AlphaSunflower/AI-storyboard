package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.handler.OrchestrationRequest;
import com.moon.moonagent.ai.agent.agentscope.workflow.AgentWorkflowService;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
final class TurnContextMiddleware implements MiddlewareBase {

    private final AgentWorkflowService workflows;

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext context, String prompt) {
        if (context == null) return Mono.just(prompt);
        AgentScopeRunContext runContext = context.get(AgentScopeRunContext.class);
        if (runContext == null || runContext.request() == null) return Mono.just(prompt);
        OrchestrationRequest request = runContext.request();
        List<String> lines = new ArrayList<>();
        AgentScopeTurnState turnState = runContext.turnState();
        if (turnState != null) {
            lines.add("Reference input mode: " + turnState.referenceInputMode().name());
            switch (turnState.referenceInputMode()) {
                case ASSET_ONLY -> lines.add(
                        "Asset handling: call list_asset_groups first and use its file contentUrl values. "
                                + "Do not describe the asset group as a user-uploaded image.");
                case UPLOAD_ONLY -> lines.add(
                        "Upload handling: attached images are already supplied as native image content. "
                                + "Inspect them directly and do not call web_fetch or "
                                + "list_asset_groups or ask for asset groups.");
                case MIXED -> lines.add(
                        "Mixed handling: call list_asset_groups for asset metadata, keep uploaded image URLs, "
                                + "and use the reference-locator subagent when both sources need reconciliation. "
                                + "Choose only the files that match the user's intent.");
                default -> { }
            }
        }
        if (request.getPicUrls() != null && !request.getPicUrls().isEmpty()) {
            lines.add("Current turn attached image count: " + request.getPicUrls().size()
                    + ". The image bytes are already attached to the user message.");
        }
        if (request.getAssetIds() != null && !request.getAssetIds().isEmpty()) {
            lines.add("Current turn selected asset IDs: " + request.getAssetIds());
        }
        if (request.getReferenceVideos() != null && !request.getReferenceVideos().isEmpty()) {
            lines.add("Current turn reference video URLs: " + request.getReferenceVideos());
        }
        if (runContext.conversation() != null) {
            String workflow = workflows.activeContext(runContext.conversation().getId());
            if (!workflow.isBlank()) lines.add(workflow);
        }
        if (lines.isEmpty()) return Mono.just(prompt);
        return Mono.just((prompt == null ? "" : prompt)
                + "\n\n<turn-context>\n" + String.join("\n", lines) + "\n</turn-context>");
    }
}
