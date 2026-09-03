package com.moon.moonagent.ai.agent.impl;

import com.moon.moonagent.ai.ImageGenerationService;
import com.moon.moonagent.ai.VideoGenerationService;
import com.moon.moonagent.client.StoryboardClient;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentAssetMapper;
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
    private AgentAssetMapper agentAssetMapper;

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

        ArgumentCaptor<AgentAsset> captor = ArgumentCaptor.forClass(AgentAsset.class);
        verify(agentAssetMapper).insert(captor.capture());
        AgentAsset record = captor.getValue();
        assertEquals("conv-1", record.getConversationId());
        assertEquals("video", record.getType());
        assertEquals("storyboard_video", record.getGenType());
        assertEquals("completed", record.getStatus());
        assertEquals("scene-1", record.getSceneId());
        assertEquals("task-v1", record.getTaskId());
        assertEquals("测试提示词", record.getPrompt());
        assertEquals("MiniMax-H3", record.getModel());
        assertEquals("/api/files/videos/v.mp4", record.getUrl());
        assertEquals(1234L, record.getDurationMs());
        assertNull(record.getError());
    }
}
