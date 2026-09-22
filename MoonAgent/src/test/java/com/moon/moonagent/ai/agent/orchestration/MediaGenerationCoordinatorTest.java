package com.moon.moonagent.ai.agent.orchestration;

import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.service.FileStorageService;
import com.moon.moonagent.ai.VideoGenerationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaGenerationCoordinatorTest {

    @Mock AgentAssetMapper assetMapper;
    @Mock FileStorageService fileStorageService;
    @Mock VideoGenerationService videoGenerationService;

    @Test
    void videoRequestCreatesQueuedGenerationRecordWithTrustedOwnership() {
        MediaGenerationCoordinator coordinator = new MediaGenerationCoordinator(
                assetMapper, fileStorageService, videoGenerationService);
        ReflectionTestUtils.setField(coordinator, "internalBaseUrl", "http://localhost:8082");
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setUserId("u1");
        conversation.setProjectId("p1");
        conversation.setEngineVersion("unified-loop-v1");

        MediaGenerationCoordinator.Prepared prepared = coordinator.prepare(
                conversation, "generate_video", "{\"prompt\":\"镜头推进\"}");

        ArgumentCaptor<AgentAsset> captor = ArgumentCaptor.forClass(AgentAsset.class);
        verify(assetMapper).insert(captor.capture());
        AgentAsset record = captor.getValue();
        assertThat(record.getType()).isEqualTo("video");
        assertThat(record.getStatus()).isEqualTo("queued");
        assertThat(record.getUserId()).isEqualTo("u1");
        assertThat(record.getProjectId()).isEqualTo("p1");
        assertThat(record.getIdempotencyKey()).startsWith("generate_video:");
        assertThat(prepared.toolInput()).contains("\"conversationId\":\"c1\"");
    }

    @Test
    void imageRequestCreatesGeneratingRecord() {
        MediaGenerationCoordinator coordinator = new MediaGenerationCoordinator(
                assetMapper, fileStorageService, videoGenerationService);
        ReflectionTestUtils.setField(coordinator, "internalBaseUrl", "http://localhost:8082");
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setUserId("u1");
        conversation.setProjectId("p1");

        coordinator.prepare(conversation, "generate_image",
                "{\"mode\":\"TEXT_TO_IMAGE\",\"prompt\":\"海报\"}");

        ArgumentCaptor<AgentAsset> captor = ArgumentCaptor.forClass(AgentAsset.class);
        verify(assetMapper).insert(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("image");
        assertThat(captor.getValue().getStatus()).isEqualTo("generating");
    }

    @Test
    void sameIdempotencyKeyReusesExistingRecord() {
        java.util.concurrent.atomic.AtomicReference<AgentAsset> stored = new java.util.concurrent.atomic.AtomicReference<>();
        when(assetMapper.selectOne(any())).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        }).when(assetMapper).insert(any(AgentAsset.class));
        MediaGenerationCoordinator coordinator = new MediaGenerationCoordinator(
                assetMapper, fileStorageService, videoGenerationService);
        AgentConversation conversation = new AgentConversation();
        conversation.setId("c1");
        conversation.setUserId("u1");
        conversation.setProjectId("p1");

        MediaGenerationCoordinator.Prepared first = coordinator.prepare(
                conversation, "generate_video", "{\"prompt\":\"x\"}", "stable-key");
        MediaGenerationCoordinator.Prepared second = coordinator.prepare(
                conversation, "generate_video", "{\"prompt\":\"x\"}", "stable-key");

        assertThat(second.record()).isSameAs(first.record());
        verify(assetMapper, times(1)).insert(any(AgentAsset.class));
    }

    @Test
    void arrayToolResultIsPersistedAsControlledImageUrl() {
        AgentAsset record = new AgentAsset();
        record.setId("a1");
        record.setType("image");
        record.setStatus("generating");
        record.setCreatedAt(java.time.OffsetDateTime.now().minusSeconds(1));
        when(assetMapper.selectById("a1")).thenReturn(record);
        when(fileStorageService.saveImage("http://provider/image.png"))
                .thenReturn("/api/files/images/image.png");
        MediaGenerationCoordinator coordinator = new MediaGenerationCoordinator(
                assetMapper, fileStorageService, videoGenerationService);

        coordinator.complete(new MediaGenerationCoordinator.Prepared(record, "{}"),
                "[{\"url\":\"http://provider/image.png\"}]");

        assertThat(record.getStatus()).isEqualTo("completed");
        assertThat(record.getUrl()).isEqualTo("/api/files/images/image.png");
        verify(fileStorageService).saveImage("http://provider/image.png");
        verify(assetMapper).updateById(record);
    }

    @Test
    void mcpTextContentIsUnwrappedAndPersistedAsControlledImageUrl() {
        AgentAsset record = new AgentAsset();
        record.setId("a1");
        record.setType("image");
        record.setStatus("generating");
        record.setCreatedAt(java.time.OffsetDateTime.now().minusSeconds(1));
        when(assetMapper.selectById("a1")).thenReturn(record);
        when(fileStorageService.saveImage("http://provider/image.png"))
                .thenReturn("/api/files/images/image.png");
        MediaGenerationCoordinator coordinator = new MediaGenerationCoordinator(
                assetMapper, fileStorageService, videoGenerationService);

        com.moon.moonagent.orchestration.MediaToolResult result = coordinator.complete(
                new MediaGenerationCoordinator.Prepared(record, "{}"),
                "[{\"text\":\"{\\\"data\\\":[{\\\"url\\\":\\\"http://provider/image.png\\\"}],"
                        + "\\\"model\\\":\\\"doubao-seedream\\\",\\\"mediaType\\\":\\\"image\\\","
                        + "\\\"status\\\":\\\"completed\\\"}\"}]");

        assertThat(record.getStatus()).isEqualTo("completed");
        assertThat(record.getModel()).isEqualTo("doubao-seedream");
        assertThat(record.getUrl()).isEqualTo("/api/files/images/image.png");
        assertThat(result.resultUrls()).containsExactly("/api/files/images/image.png");
        verify(fileStorageService).saveImage("http://provider/image.png");
    }
}
