package com.moon.moonagent.ai.agent.sandbox;

import com.moon.moonagent.entity.AgentConversation;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SandboxOutputReconcilerTest {

    @Test
    void publishesEveryOutputFile() {
        MoonSandboxClient client = mock(MoonSandboxClient.class);
        SandboxArtifactPublisher publisher = mock(SandboxArtifactPublisher.class);
        AgentConversation conversation = conversation();
        when(client.enabled()).thenReturn(true);
        when(client.list("c1", "output")).thenReturn(List.of(
                new SandboxFileEntry("output", true, 0, "", OffsetDateTime.now()),
                new SandboxFileEntry("output/report.md", false, 10, "text/markdown",
                        OffsetDateTime.now())));
        when(publisher.publishPath(org.mockito.ArgumentMatchers.eq(conversation),
                org.mockito.ArgumentMatchers.eq("turn-1"),
                org.mockito.ArgumentMatchers.eq("output/report.md"),
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq("text/markdown")))
                .thenReturn(new SandboxArtifactPublisher.PublishedArtifacts(
                        List.of("asset-1"), List.of("/api/files/files/report.md")));
        SandboxOutputReconciler reconciler = new SandboxOutputReconciler(client, publisher);

        var result = reconciler.reconcile(conversation, "turn-1");

        assertThat(result.assetIds()).containsExactly("asset-1");
        assertThat(result.urls()).containsExactly("/api/files/files/report.md");
    }

    private static AgentConversation conversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setUserId("u1");
        conversation.setProjectId("p1");
        return conversation;
    }
}
