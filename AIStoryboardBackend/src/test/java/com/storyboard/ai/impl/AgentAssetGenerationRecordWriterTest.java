package com.storyboard.ai.impl;

import com.storyboard.common.generation.GenerationRecord;
import com.storyboard.common.generation.GenerationRecordTypes;
import com.storyboard.common.generation.GenerationRecordUpdate;
import com.storyboard.entity.AgentAsset;
import com.storyboard.mapper.AgentAssetMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentAssetGenerationRecordWriterTest {

    @Test
    void createMapsProjectOwnedEditorGenerationRecord() {
        AgentAssetMapper mapper = mock(AgentAssetMapper.class);
        AgentAssetGenerationRecordWriter writer = new AgentAssetGenerationRecordWriter(mapper);

        writer.create(new GenerationRecord(
                "user-1", "project-1", null, "scene-1",
                GenerationRecordTypes.IMAGE, GenerationRecordTypes.STORYBOARD_IMAGE,
                "prompt", "model", null, "/api/files/images/a.png",
                List.of("/api/files/images/a.png"), List.of(), Map.of("n", 1),
                GenerationRecordTypes.COMPLETED, null, 100L, OffsetDateTime.now()));

        ArgumentCaptor<AgentAsset> captor = ArgumentCaptor.forClass(AgentAsset.class);
        verify(mapper).insert(captor.capture());
        AgentAsset record = captor.getValue();
        assertEquals("user-1", record.getUserId());
        assertEquals("project-1", record.getProjectId());
        assertEquals("scene-1", record.getSceneId());
        assertEquals(GenerationRecordTypes.STORYBOARD_IMAGE, record.getGenType());
        assertTrue(record.getResultUrls().contains("/api/files/images/a.png"));
    }

    @Test
    void updateByTaskIdMarksSceneVideoCompleted() {
        AgentAssetMapper mapper = mock(AgentAssetMapper.class);
        AgentAssetGenerationRecordWriter writer = new AgentAssetGenerationRecordWriter(mapper);
        AgentAsset record = new AgentAsset();
        record.setId("a1");
        record.setTaskId("task-1");
        record.setCreatedAt(OffsetDateTime.now().minusSeconds(2));
        when(mapper.selectList(any())).thenReturn(List.of(record));

        writer.updateByTaskId("task-1", GenerationRecordUpdate.completed(
                "/api/files/videos/v.mp4", List.of("/api/files/videos/v.mp4"), 2000L));

        verify(mapper).updateById(record);
        assertEquals(GenerationRecordTypes.COMPLETED, record.getStatus());
        assertEquals("/api/files/videos/v.mp4", record.getUrl());
    }
}
