package com.moon.moonagent.ai.agent.agentscope.sandbox;

import com.moon.moonagent.ai.agent.agentscope.AgentScopeRunContext;
import com.moon.moonagent.ai.agent.sandbox.SandboxArtifactPublisher;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentConversationMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryRequest;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryResult;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Publishes artifacts the agent explicitly delivers via {@code deliver_artifact}.
 *
 * <p>Shares the content-hash idempotency key with the turn-end reconciler so the two artifact
 * tracks collapse onto one {@code agent_assets} row. Delivered asset ids are recorded on the
 * per-turn state so the runtime can backfill the assistant message metadata and the frontend file
 * card even for files written outside {@code output/}.
 */
@Component
@ConditionalOnProperty(prefix = "ai.agent.sandbox", name = "enabled", havingValue = "true")
public class MoonArtifactDeliveryTarget implements ArtifactDeliveryTarget {

    private final SandboxArtifactPublisher publisher;
    private final AgentConversationMapper conversations;

    public MoonArtifactDeliveryTarget(SandboxArtifactPublisher publisher,
                                      AgentConversationMapper conversations) {
        this.publisher = publisher;
        this.conversations = conversations;
    }

    @Override
    public ArtifactDeliveryResult deliver(RuntimeContext runtimeContext,
                                          ArtifactDeliveryRequest request) {
        if (request == null) {
            return ArtifactDeliveryResult.fail("delivery request is missing");
        }
        AgentScopeRunContext runContext =
                runtimeContext == null ? null : runtimeContext.get(AgentScopeRunContext.class);
        AgentConversation conversation = resolve(runContext, runtimeContext);
        if (conversation == null) {
            return ArtifactDeliveryResult.fail("conversation not found for artifact delivery");
        }
        SandboxArtifactPublisher.DeliveryOutcome outcome = publisher.deliver(
                conversation, null, request.filePath(), request.fileName(),
                request.content(), null, request.force());
        if (outcome.conflict()) {
            return ArtifactDeliveryResult.conflict(outcome.error());
        }
        if (outcome.artifacts().assetIds().isEmpty()) {
            return ArtifactDeliveryResult.fail(outcome.error() == null
                    ? "artifact was not stored" : outcome.error());
        }
        if (runContext != null && runContext.turnState() != null) {
            outcome.artifacts().assetIds().forEach(runContext.turnState()::addDeliveredAsset);
        }
        String url = outcome.artifacts().urls().isEmpty()
                ? null : outcome.artifacts().urls().get(0);
        return ArtifactDeliveryResult.success(url);
    }

    private AgentConversation resolve(AgentScopeRunContext runContext, RuntimeContext runtime) {
        if (runContext != null && runContext.conversation() != null) {
            return runContext.conversation();
        }
        String sessionId = runtime == null ? null : runtime.getSessionId();
        return sessionId == null || sessionId.isBlank() ? null : conversations.selectById(sessionId);
    }
}
