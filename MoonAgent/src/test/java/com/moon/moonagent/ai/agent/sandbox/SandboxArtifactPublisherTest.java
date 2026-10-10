package com.moon.moonagent.ai.agent.sandbox;

import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.service.FileStorageService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SandboxArtifactPublisherTest {

    @Test
    void publishesDocumentAndChartArtifacts() {
        MoonSandboxClient client = mock(MoonSandboxClient.class);
        FileStorageService storage = mock(FileStorageService.class);
        AgentAssetMapper mapper = mock(AgentAssetMapper.class);
        when(client.download(eq("c1"), any())).thenReturn(new byte[]{1, 2, 3});
        when(storage.saveGeneratedFile(any(), eq("report.md"), eq("text/markdown")))
                .thenReturn("/api/files/files/report.md?token=x");
        when(storage.saveGeneratedFile(any(), eq("chart.png"), eq("image/png")))
                .thenReturn("/api/files/files/chart.png?token=x");
        doAnswer(invocation -> {
            AgentAsset asset = invocation.getArgument(0);
            asset.setId(asset.getType() + "-id");
            return 1;
        }).when(mapper).insert(any(AgentAsset.class));
        SandboxArtifactPublisher publisher = new SandboxArtifactPublisher(client, storage, mapper);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setUserId("u1");
        conversation.setProjectId("p1");

        var result = publisher.publish(conversation, "task-1", new SandboxExecutionResult(
                0, "ok", "", false, false, false, 5,
                List.of(
                        new SandboxFileView("output/report.md", 3, "text/markdown"),
                        new SandboxFileView("output/chart.png", 3, "image/png"))));

        assertThat(result.assetIds()).containsExactly("document-id", "image-id");
        assertThat(result.urls()).containsExactly(
                "/api/files/files/report.md?token=x",
                "/api/files/files/chart.png?token=x");
    }
}
