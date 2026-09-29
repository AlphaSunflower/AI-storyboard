package com.moon.moonagent.service.impl;

import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.ai.video.ReferenceImage;
import com.moon.moonagent.ai.video.ReferenceImageRole;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.dto.request.StoryboardVideoEnqueueItem;
import com.moon.moonagent.dto.request.StoryboardVideoEnqueueRequest;
import com.moon.moonagent.entity.Scene;
import com.moon.moonagent.entity.StoryboardVideoQueue;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.mapper.StoryboardVideoQueueMapper;
import com.moon.moonagent.service.AssetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
class StoryboardVideoQueueServiceImplTest {

    @Mock
    private StoryboardVideoQueueMapper queueMapper;
    @Mock
    private AgentConversationMapper conversationMapper;
    @Mock
    private StoryboardClient storyboardClient;
    @Mock
    private AssetService assetService;
    @Mock
    private AgentGenerationService generationService;

    @InjectMocks
    private StoryboardVideoQueueServiceImpl service;

    private Scene scene;

    @BeforeEach
    void setUp() {
        scene = new Scene();
        scene.setId("scene-1");
        scene.setProjectId("proj-1");
        scene.setImageUrl("/api/files/images/a.png");
        lenient().doReturn(scene).when(storyboardClient).getScene("scene-1");
        // 入队后 process() 触发的 drain 应立即发现无 queued 任务而退出
        lenient().when(queueMapper.findNextQueued()).thenReturn(null);
    }

    @Test
    void enqueue_createsQueuedRow_andMarksScene() {
        StoryboardVideoEnqueueRequest request = new StoryboardVideoEnqueueRequest(
                "conv-1", "proj-1",
                List.of(new StoryboardVideoEnqueueItem(
                        "scene-1", "测试提示词", "MiniMax-H3", "1080p", null,
                        "16:9", 8, null, scene.getImageUrl(), null, null, java.util.List.of())));

        List<StoryboardVideoQueue> rows = service.enqueue(request);

        assertEquals(1, rows.size());
        assertEquals("queued", rows.get(0).getStatus());
        assertEquals("scene-1", rows.get(0).getSceneId());
        assertEquals("测试提示词", rows.get(0).getPrompt());
        verify(queueMapper, times(1)).insert(any(StoryboardVideoQueue.class));
        verify(storyboardClient, times(1)).updateScene(scene);
    }

    @Test
    void enqueue_emptyItems_throws() {
        StoryboardVideoEnqueueRequest request = new StoryboardVideoEnqueueRequest("conv-1", "proj-1", List.of());
        assertThrows(IllegalArgumentException.class, () -> service.enqueue(request));
    }

    @Test
    void enqueue_withRefImageRoles_serializesJsonRefImageUrls() {
        StoryboardVideoEnqueueItem item = new StoryboardVideoEnqueueItem(
                "scene-1", "测试提示词", "MiniMax-H3", "1080p", null,
                "16:9", 8, null, null, List.of("u1", "u2"), null,
                List.of(Map.of("url", "u1", "role", "FIRST_FRAME"),
                        Map.of("url", "u2", "role", "REFERENCE")));
        StoryboardVideoEnqueueRequest request = new StoryboardVideoEnqueueRequest("conv-1", "proj-1", List.of(item));

        service.enqueue(request);

        ArgumentCaptor<StoryboardVideoQueue> captor = ArgumentCaptor.forClass(StoryboardVideoQueue.class);
        verify(queueMapper).insert(captor.capture());
        String ref = captor.getValue().getRefImageUrls();
        assertNotNull(ref);
        assertTrue(ref.startsWith("["), "带角色参考图应序列化为 JSON 数组，实际=" + ref);
        assertTrue(ref.contains("FIRST_FRAME"));
        assertTrue(ref.contains("u1"));
    }

    @Test
    void enqueue_skipsSceneWithActiveQueueRow() {
        when(queueMapper.selectCount(any())).thenReturn(1L);
        StoryboardVideoEnqueueRequest request = new StoryboardVideoEnqueueRequest(
                "conv-1", "proj-1",
                List.of(new StoryboardVideoEnqueueItem(
                        "scene-1", "测试提示词", "MiniMax-H3", "1080p", null,
                        "16:9", 8, null, null, null, null, List.of())));

        List<StoryboardVideoQueue> rows = service.enqueue(request);

        assertTrue(rows.isEmpty());
        verify(queueMapper, times(0)).insert(any(StoryboardVideoQueue.class));
    }

    @Test
    void prepareManagedFrames_usesCurrentAndNextSceneImages() {
        Scene next = new Scene();
        next.setId("scene-2");
        next.setProjectId("proj-1");
        next.setSceneNumber(2);
        next.setImageUrl("/api/files/images/next.png");
        when(storyboardClient.getSceneReferences("scene-1")).thenReturn(List.of());
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conv-1");

        service.prepareManagedFrames(conversation, scene, next);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> frames = ArgumentCaptor.forClass(List.class);
        verify(storyboardClient).replaceManagedVideoFrames(eq("scene-1"), frames.capture());
        assertEquals(2, frames.getValue().size());
        assertEquals("FIRST_FRAME", frames.getValue().get(0).get("role"));
        assertEquals(scene.getImageUrl(), frames.getValue().get(0).get("url"));
        assertEquals("LAST_FRAME", frames.getValue().get(1).get("role"));
        assertEquals(next.getImageUrl(), frames.getValue().get(1).get("url"));
    }

    @Test
    void prepareManagedFrames_preservesExplicitUserFrames() {
        Scene next = new Scene();
        next.setId("scene-2");
        next.setProjectId("proj-1");
        next.setSceneNumber(2);
        next.setImageUrl("/api/files/images/next.png");
        when(storyboardClient.getSceneReferences("scene-1")).thenReturn(List.of(
                Map.of("type", "image", "url", "/upload/first.png",
                        "role", "FIRST_FRAME", "sourceType", "upload")));
        AgentConversation conversation = new AgentConversation();
        conversation.setId("conv-1");

        service.prepareManagedFrames(conversation, scene, next);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> frames = ArgumentCaptor.forClass(List.class);
        verify(storyboardClient).replaceManagedVideoFrames(eq("scene-1"), frames.capture());
        assertEquals(1, frames.getValue().size());
        assertEquals("LAST_FRAME", frames.getValue().get(0).get("role"));
        assertEquals(next.getImageUrl(), frames.getValue().get(0).get("url"));
    }

    @Test
    void cancel_queued_setsCancelledAndMarksScene() {
        StoryboardVideoQueue row = new StoryboardVideoQueue();
        row.setId("queue-1");
        row.setSceneId("scene-1");
        row.setStatus("queued");
        when(queueMapper.selectById("queue-1")).thenReturn(row);

        service.cancel("queue-1");

        verify(queueMapper).updateById(row);
        assertEquals("cancelled", row.getStatus());
        assertEquals("cancelled", scene.getVideoStatus());
        verify(storyboardClient).updateScene(scene);
    }

    @Test
    void cancel_completed_ignored() {
        StoryboardVideoQueue row = new StoryboardVideoQueue();
        row.setId("queue-1");
        row.setSceneId("scene-1");
        row.setStatus("completed");
        when(queueMapper.selectById("queue-1")).thenReturn(row);

        service.cancel("queue-1");

        // 已终态不应再更新
        verify(queueMapper, times(0)).updateById(any(StoryboardVideoQueue.class));
    }

    @Test
    void cancel_running_callsUpstreamThenLocal() {
        StoryboardVideoQueue row = new StoryboardVideoQueue();
        row.setId("queue-1");
        row.setSceneId("scene-1");
        row.setStatus("running");
        row.setTaskId("upstream-task-1");
        when(queueMapper.selectById("queue-1")).thenReturn(row);
        when(generationService.cancelVideoTask("upstream-task-1")).thenReturn(Map.of("taskId", "upstream-task-1", "status", "cancelled"));

        service.cancel("queue-1");

        verify(generationService).cancelVideoTask("upstream-task-1");
        assertEquals("cancelled", row.getStatus());
        assertEquals("cancelled", scene.getVideoStatus());
        verify(storyboardClient).updateScene(scene);
    }

    @Test
    void listByProject_delegates() {
        StoryboardVideoQueue row = new StoryboardVideoQueue();
        row.setId("queue-1");
        when(queueMapper.findByProjectId("proj-1")).thenReturn(List.of(row));
        assertEquals(1, service.listByProject("proj-1", null).size());
    }

    @Test
    void listByProject_active_delegatesToActiveOnly() {
        StoryboardVideoQueue row = new StoryboardVideoQueue();
        row.setId("queue-1");
        when(queueMapper.findActiveByProject("proj-1")).thenReturn(List.of(row));
        assertEquals(1, service.listByProject("proj-1", "active").size());
        verify(queueMapper).findActiveByProject("proj-1");
    }

    @Test
    void sceneReferences_splitImagesAndVideosByType() {
        List<Map<String, Object>> refs = List.of(
                Map.of("type", "image", "url", "/api/files/images/ref.png", "role", "REFERENCE"),
                Map.of("type", "video", "url", "/api/files/videos/ref.mp4"),
                Map.of("type", "image", "url", "/api/files/images/first.png", "role", "FIRST_FRAME"),
                Map.of("type", "video", "url", "/api/files/videos/ignored.mp4"));

        List<ReferenceImage> images = StoryboardVideoQueueServiceImpl.mapSceneReferenceImages(refs);
        List<String> videos = StoryboardVideoQueueServiceImpl.sceneReferenceVideoUrls(refs);

        assertEquals(List.of("/api/files/images/ref.png", "/api/files/images/first.png"),
                images.stream().map(ReferenceImage::url).toList());
        assertEquals(ReferenceImageRole.FIRST_FRAME, images.get(1).role());
        assertEquals(List.of("/api/files/videos/ref.mp4"), videos);
    }
}
