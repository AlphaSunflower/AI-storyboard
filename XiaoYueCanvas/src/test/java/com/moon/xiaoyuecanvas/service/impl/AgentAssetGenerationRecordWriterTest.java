package com.moon.xiaoyuecanvas.service.impl;

import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.storyboard.common.generation.GenerationRecord;
import com.storyboard.common.generation.GenerationRecordTypes;
import com.storyboard.common.generation.GenerationReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentAssetGenerationRecordWriterTest {

    @Test
    void createPersistsFullCanvasGenerationRecord() {
        AgentAssetMapper mapper = mock(AgentAssetMapper.class);
        doAnswer(invocation -> {
            AgentAsset asset = invocation.getArgument(0);
            asset.setId("asset-1");
            return 1;
        }).when(mapper).insert(any(AgentAsset.class));
        AgentAssetGenerationRecordWriter writer = new AgentAssetGenerationRecordWriter(mapper);

        String id = writer.create(new GenerationRecord(
                "user-1", "project-1", "conversation-1", null,
                GenerationRecordTypes.IMAGE, "text_to_image", "prompt", "model-1", null,
                "/api/files/images/a.png", List.of("/api/files/images/a.png"),
                List.of(new GenerationReference("image", "/api/files/images/ref.png", "SOURCE")),
                Map.of("canvas", Map.of(
                        "canvasId", "canvas-1",
                        "producerNodeId", "node-1",
                        "batchId", "batch-1",
                        "sourceRole", "SOURCE")),
                GenerationRecordTypes.COMPLETED, null, 123L, OffsetDateTime.now()));

        assertEquals("asset-1", id);
        ArgumentCaptor<AgentAsset> captor = ArgumentCaptor.forClass(AgentAsset.class);
        verify(mapper).insert(captor.capture());
        AgentAsset asset = captor.getValue();
        assertEquals("user-1", asset.getUserId());
        assertEquals("project-1", asset.getProjectId());
        assertEquals("prompt", asset.getPrompt());
        assertEquals("text_to_image", asset.getGenType());
        assertEquals("completed", asset.getStatus());
        assertTrue(asset.getResultUrls().contains("/api/files/images/a.png"));
        assertTrue(asset.getReferenceUrls().contains("/api/files/images/ref.png"));
        assertTrue(asset.getParams().contains("\"canvasId\":\"canvas-1\""));
        assertEquals(123L, asset.getDurationMs());
    }
}
