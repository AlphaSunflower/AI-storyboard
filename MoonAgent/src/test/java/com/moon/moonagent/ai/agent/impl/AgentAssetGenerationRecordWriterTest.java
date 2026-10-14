package com.moon.moonagent.ai.agent.impl;

import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.service.asset.AssetOutputOutboxService;
import com.storyboard.common.generation.GenerationRecordUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 生成任务在用户删掉素材之后才回来时，写回必须被丢弃 ——
 * 否则被删的图/视频会带着新 URL 重新出现在会话资产里，视频还会被重新发回对话。
 */
@ExtendWith(MockitoExtension.class)
class AgentAssetGenerationRecordWriterTest {

    @Mock
    private AgentAssetMapper assetMapper;
    @Mock
    private AssetOutputOutboxService assetOutputOutboxService;

    private AgentAssetGenerationRecordWriter writer;

    @BeforeEach
    void setUp() {
        writer = new AgentAssetGenerationRecordWriter(assetMapper, assetOutputOutboxService);
    }

    private static AgentAsset deletedRecord() {
        AgentAsset record = new AgentAsset();
        record.setId("asset-1");
        record.setTaskId("task-1");
        record.setStatus(AgentAsset.STATUS_DELETED);
        return record;
    }

    private static GenerationRecordUpdate completedUpdate() {
        return GenerationRecordUpdate.completed(
                "/api/files/images/new.png", List.of("/api/files/images/new.png"), null);
    }

    @Test
    @DisplayName("update 丢弃对已删除行的写回")
    void updateSkipsDeletedRecord() {
        when(assetMapper.selectById("asset-1")).thenReturn(deletedRecord());

        writer.update("asset-1", completedUpdate());

        verify(assetMapper, never()).updateById(any(AgentAsset.class));
        verify(assetOutputOutboxService, never()).enqueue(any());
    }

    @Test
    @DisplayName("updateByTaskId 丢弃对已删除行的写回")
    void updateByTaskIdSkipsDeletedRecord() {
        when(assetMapper.selectList(any())).thenReturn(List.of(deletedRecord()));

        writer.updateByTaskId("task-1", completedUpdate());

        verify(assetMapper, never()).updateById(any(AgentAsset.class));
        verify(assetOutputOutboxService, never()).enqueue(any());
    }
}
