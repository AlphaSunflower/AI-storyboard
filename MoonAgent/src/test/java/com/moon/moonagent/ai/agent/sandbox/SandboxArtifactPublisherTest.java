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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Test
    void deliverReusesAssetAlreadyPublishedByTheOtherTrack() {
        AgentAsset existing = new AgentAsset();
        existing.setId("existing-id");
        existing.setUrl("/api/files/files/report.md?token=x");
        AgentAssetMapper mapper = mock(AgentAssetMapper.class);
        when(mapper.selectOne(any())).thenReturn(existing);
        SandboxArtifactPublisher publisher = new SandboxArtifactPublisher(
                mock(MoonSandboxClient.class), mock(FileStorageService.class), mapper);

        var outcome = publisher.deliver(conversation(), "task-1", "output/report.md",
                "report.md", new byte[]{1, 2, 3}, null, false);

        assertThat(outcome.conflict()).isFalse();
        assertThat(outcome.artifacts().assetIds()).containsExactly("existing-id");
        verify(mapper, never()).insert(any(AgentAsset.class));
    }

    @Test
    void deliverRejectsNameConflictWhenForceIsFalse() {
        AgentAssetMapper mapper = mock(AgentAssetMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.selectCount(any())).thenReturn(1L);
        SandboxArtifactPublisher publisher = new SandboxArtifactPublisher(
                mock(MoonSandboxClient.class), mock(FileStorageService.class), mapper);

        var outcome = publisher.deliver(conversation(), "task-1", "output/report.md",
                "report.md", new byte[]{4, 5, 6}, null, false);

        assertThat(outcome.conflict()).isTrue();
        assertThat(outcome.artifacts().assetIds()).isEmpty();
        verify(mapper, never()).insert(any(AgentAsset.class));
    }

    @Test
    void publishesArbitraryExtensionsAndClassifiesMediaTypes() {
        MoonSandboxClient client = mock(MoonSandboxClient.class);
        FileStorageService storage = mock(FileStorageService.class);
        AgentAssetMapper mapper = mock(AgentAssetMapper.class);
        when(client.download(eq("c1"), any())).thenReturn(new byte[]{1, 2, 3});
        when(storage.saveGeneratedFile(any(), any(), any()))
                .thenAnswer(invocation -> "/api/files/files/" + invocation.getArgument(1));
        doAnswer(invocation -> {
            AgentAsset asset = invocation.getArgument(0);
            asset.setId(asset.getType() + "-" + asset.getOriginalName());
            return 1;
        }).when(mapper).insert(any(AgentAsset.class));
        SandboxArtifactPublisher publisher = new SandboxArtifactPublisher(client, storage, mapper);

        var result = publisher.publish(conversation(), "task-1", new SandboxExecutionResult(
                0, "ok", "", false, false, false, 5,
                List.of(
                        new SandboxFileView("output/tool.py", 3, null),
                        new SandboxFileView("output/clip.mp4", 3, "video/mp4"),
                        new SandboxFileView("output/cover.webp", 3, null),
                        new SandboxFileView("output/bundle.zip", 3, null),
                        new SandboxFileView("output/Makefile", 3, null))));

        assertThat(result.assetIds()).containsExactly(
                "document-tool.py",
                "video-clip.mp4",
                "image-cover.webp",
                "document-bundle.zip",
                "document-Makefile");
    }

    @Test
    void skipsArtifactsLargerThanThePublishLimit() {
        MoonSandboxClient client = mock(MoonSandboxClient.class);
        SandboxArtifactPublisher publisher = new SandboxArtifactPublisher(
                client, mock(FileStorageService.class), mock(AgentAssetMapper.class));

        var result = publisher.publishPath(conversation(), "task-1", "output/big.iso",
                50L * 1024 * 1024 + 1, null);

        assertThat(result.assetIds()).isEmpty();
        verify(client, never()).download(any(), any());
    }

    @Test
    void deliversArbitraryExtensionUsingFallbackContentType() {
        AgentAssetMapper mapper = mock(AgentAssetMapper.class);
        FileStorageService storage = mock(FileStorageService.class);
        when(storage.saveGeneratedFile(any(), eq("deploy.sh"), eq("text/plain")))
                .thenReturn("/api/files/files/deploy.sh?token=x");
        doAnswer(invocation -> {
            AgentAsset asset = invocation.getArgument(0);
            asset.setId(asset.getType() + "-" + asset.getOriginalName());
            return 1;
        }).when(mapper).insert(any(AgentAsset.class));
        SandboxArtifactPublisher publisher = new SandboxArtifactPublisher(
                mock(MoonSandboxClient.class), storage, mapper);

        var outcome = publisher.deliver(conversation(), "task-1", "output/deploy.sh",
                "deploy.sh", new byte[]{1, 2, 3}, null, false);

        assertThat(outcome.conflict()).isFalse();
        assertThat(outcome.error()).isNull();
        assertThat(outcome.artifacts().assetIds()).containsExactly("document-deploy.sh");
    }

    private static AgentConversation conversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setUserId("u1");
        conversation.setProjectId("p1");
        return conversation;
    }
}
