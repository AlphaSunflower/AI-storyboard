package com.moon.moonagent.ai.agent.sandbox;

import com.moon.moonagent.entity.AgentConversation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SandboxOutputReconciler {

    private static final Logger log = LoggerFactory.getLogger(SandboxOutputReconciler.class);

    private final MoonSandboxClient client;
    private final SandboxArtifactPublisher publisher;

    public SandboxOutputReconciler(MoonSandboxClient client,
                                   SandboxArtifactPublisher publisher) {
        this.client = client;
        this.publisher = publisher;
    }

    public SandboxArtifactPublisher.PublishedArtifacts reconcile(
            AgentConversation conversation, String taskId) {
        if (conversation == null || !client.enabled()) {
            return SandboxArtifactPublisher.PublishedArtifacts.EMPTY;
        }
        List<String> assetIds = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        try {
            for (SandboxFileEntry file : client.list(conversation.getId(), "output")) {
                if (file.directory()) continue;
                SandboxArtifactPublisher.PublishedArtifacts published =
                        publisher.publishPath(conversation, taskId,
                                file.path(), file.sizeBytes(), file.mimeType());
                assetIds.addAll(published.assetIds());
                urls.addAll(published.urls());
            }
        } catch (RuntimeException error) {
            log.warn("Sandbox output reconciliation failed conversationId={}: {}",
                    conversation.getId(), error.getMessage());
        }
        return new SandboxArtifactPublisher.PublishedArtifacts(
                List.copyOf(assetIds), List.copyOf(urls));
    }
}
