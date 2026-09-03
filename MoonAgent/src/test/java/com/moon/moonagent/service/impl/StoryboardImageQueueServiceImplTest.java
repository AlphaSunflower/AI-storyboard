package com.moon.moonagent.service.impl;

import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.dto.request.StoryboardImageEnqueueItem;
import com.moon.moonagent.dto.request.StoryboardImageEnqueueRequest;
import com.moon.moonagent.entity.StoryboardImageTask;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.moon.moonagent.mapper.StoryboardImageTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StoryboardImageQueueServiceImplTest {

    @Mock
    private StoryboardImageTaskMapper queueMapper;
    @Mock
    private AgentConversationMapper conversationMapper;
    @Mock
    private StoryboardClient storyboardClient;
    @Mock
    private AgentGenerationService generationService;

    @InjectMocks
    private StoryboardImageQueueServiceImpl service;

    @BeforeEach
    void setUp() {
        // 入队后 process() 触发的 drain 应立即发现无 queued 任务而退出
        lenient().when(queueMapper.findNextQueued()).thenReturn(null);
    }

    @Test
    void enqueue_createsQueuedRow() {
        StoryboardImageEnqueueRequest request = new StoryboardImageEnqueueRequest(
                "conv-1", "proj-1",
                List.of(new StoryboardImageEnqueueItem(
                        "scene-1", "把色调调暖", "gpt-image-2", "1024x1024", "high", 1,
                        "/api/files/images/a.png", null)));

        List<StoryboardImageTask> rows = service.enqueue(request);

        assertEquals(1, rows.size());
        assertEquals("queued", rows.get(0).getStatus());
        assertEquals("scene-1", rows.get(0).getSceneId());
        assertEquals("把色调调暖", rows.get(0).getPrompt());
        assertEquals("edit", rows.get(0).getMode());
        verify(queueMapper, times(1)).insert(any(StoryboardImageTask.class));
    }

    @Test
    void enqueue_emptyItems_throws() {
        StoryboardImageEnqueueRequest request = new StoryboardImageEnqueueRequest("conv-1", "proj-1", List.of());
        assertThrows(IllegalArgumentException.class, () -> service.enqueue(request));
    }

    @Test
    void cancel_queued_setsCancelled() {
        StoryboardImageTask row = new StoryboardImageTask();
        row.setId("queue-1");
        row.setSceneId("scene-1");
        row.setStatus("queued");
        when(queueMapper.selectById("queue-1")).thenReturn(row);

        service.cancel("queue-1");

        verify(queueMapper).updateById(row);
        assertEquals("cancelled", row.getStatus());
    }

    @Test
    void cancel_completed_ignored() {
        StoryboardImageTask row = new StoryboardImageTask();
        row.setId("queue-1");
        row.setSceneId("scene-1");
        row.setStatus("completed");
        when(queueMapper.selectById("queue-1")).thenReturn(row);

        service.cancel("queue-1");

        verify(queueMapper, times(0)).updateById(any(StoryboardImageTask.class));
    }

    @Test
    void listByProject_delegates() {
        StoryboardImageTask row = new StoryboardImageTask();
        row.setId("queue-1");
        when(queueMapper.findByProjectId("proj-1")).thenReturn(List.of(row));
        assertEquals(1, service.listByProject("proj-1", null).size());
    }

    @Test
    void listByProject_active_delegatesToActiveOnly() {
        StoryboardImageTask row = new StoryboardImageTask();
        row.setId("queue-1");
        when(queueMapper.findActiveByProject("proj-1")).thenReturn(List.of(row));
        assertEquals(1, service.listByProject("proj-1", "active").size());
        verify(queueMapper).findActiveByProject("proj-1");
    }
}
