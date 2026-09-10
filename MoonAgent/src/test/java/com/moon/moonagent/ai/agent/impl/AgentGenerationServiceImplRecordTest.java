package com.moon.moonagent.ai.agent.impl;

import com.moon.moonagent.ai.ImageGenerationService;
import com.moon.moonagent.ai.VideoGenerationService;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.entity.AgentConversation;
import com.storyboard.common.generation.GenerationRecord;
import com.storyboard.common.generation.GenerationRecordWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AgentGenerationServiceImplRecordTest {

    @Mock
    private ImageGenerationService imageService;
    @Mock
    private VideoGenerationService videoService;
    @Mock
    private StoryboardClient storyboardClient;
    @Mock
    private GenerationRecordWriter generationRecordWriter;

    @InjectMocks
    private AgentGenerationServiceImpl service;

    @Test
    void recordStoryboardGeneration_writesAssetWithMapping() {
        AgentConversation conv = new AgentConversation();
        conv.setId("conv-1");
        conv.setProjectId("proj-1");

        service.recordStoryboardGeneration(conv, "scene-1", "video", "storyboard_video",
                "测试提示词", "MiniMax-H3", "task-v1", "/api/files/videos/v.mp4",
                List.of("/api/files/videos/v.mp4"), "completed", null, 1234L,
                Map.of("model", "MiniMax-H3", "duration", 8));

        ArgumentCaptor<GenerationRecord> captor = ArgumentCaptor.forClass(GenerationRecord.class);
        verify(generationRecordWriter).create(captor.capture());
        GenerationRecord record = captor.getValue();
        assertEquals("conv-1", record.conversationId());
        assertEquals("proj-1", record.projectId());
        assertEquals("video", record.type());
        assertEquals("storyboard_video", record.genType());
        assertEquals("completed", record.status());
        assertEquals("scene-1", record.sceneId());
        assertEquals("task-v1", record.taskId());
        assertEquals("测试提示词", record.prompt());
        assertEquals("MiniMax-H3", record.model());
        assertEquals("/api/files/videos/v.mp4", record.url());
        assertEquals(1234L, record.durationMs());
        assertNull(record.error());
    }
}
