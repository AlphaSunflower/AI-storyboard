package com.moon.moonagent.ai.agent.impl;

import com.moon.moonagent.ai.agent.AgentGenerationService;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.entity.AgentConversation;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.moonagent.mapper.AgentConversationMapper;
import com.storyboard.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenerationRecordServiceImplTest {

    @Mock
    private AgentAssetMapper assetMapper;
    @Mock
    private AgentConversationMapper conversationMapper;
    @Mock
    private AgentGenerationService generationService;
    @InjectMocks
    private GenerationRecordServiceImpl service;

    private AgentConversation conv() {
        AgentConversation conv = new AgentConversation();
        conv.setId("conv-1");
        conv.setUserId("user-1");
        return conv;
    }

    @Test
    void retryRejectsNonFailed() {
        AgentAsset record = asset("video", "queued");
        when(assetMapper.selectById("a1")).thenReturn(record);
        when(conversationMapper.selectById("conv-1")).thenReturn(conv());
        BusinessException ex = assertThrows(BusinessException.class, () -> service.retry("user-1", "a1"));
        assertEquals(40001, ex.getCode());
    }

    @Test
    void retryImageCreatesNewRecord() {
        AgentAsset record = asset("image", "failed");
        record.setGenType("image_to_image");
        record.setParams("{\"n\":1,\"size\":\"1024x1024\",\"mode\":\"edit\"}");
        record.setReferenceUrls("[{\"type\":\"image\",\"role\":\"source\",\"url\":\"/api/files/images/a.png\"}]");
        when(assetMapper.selectById("a1")).thenReturn(record);
        when(conversationMapper.selectById("conv-1")).thenReturn(conv());
        when(generationService.generateImage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("imageUrls", List.of("u"), "assetIds", List.of("new-1")));
        Map<String, String> result = service.retry("user-1", "a1");
        assertEquals("new-1", result.get("id"));
    }

    @Test
    void refetchRejectsImage() {
        AgentAsset record = asset("image", "failed");
        when(assetMapper.selectById("a1")).thenReturn(record);
        when(conversationMapper.selectById("conv-1")).thenReturn(conv());
        BusinessException ex = assertThrows(BusinessException.class, () -> service.refetch("user-1", "a1"));
        assertEquals(40001, ex.getCode());
    }

    @Test
    void refetchVideoCompleted() {
        AgentAsset record = asset("video", "failed");
        record.setTaskId("task-1");
        when(assetMapper.selectById("a1")).thenReturn(record);
        when(conversationMapper.selectById("conv-1")).thenReturn(conv());
        when(generationService.pollVideoTask("task-1"))
                .thenReturn(Map.of("status", "completed", "videoUrl", "/api/files/videos/v.mp4"));
        AgentAsset updated = service.refetch("user-1", "a1");
        assertEquals("completed", updated.getStatus());
        assertTrue(updated.getResultUrls().contains("v.mp4"));
    }

    @Test
    void listRecordsAggregatesPagedResult() {
        AgentAsset record = asset("image", "completed");
        when(assetMapper.selectCount(any())).thenReturn(1L);
        when(assetMapper.selectList(any())).thenReturn(List.of(record));
        Map<String, Object> result = service.listRecords("user-1", null, null, null, null, 1, 20);
        assertEquals(1L, result.get("total"));
        assertEquals(1, ((List<?>) result.get("records")).size());
        assertEquals(20, result.get("size"));
    }

    @Test
    void getRecordAllowsProjectOwnedEditorRecordWithoutConversation() {
        AgentAsset record = asset("image", "completed");
        record.setConversationId(null);
        record.setUserId("user-1");
        record.setProjectId("project-1");
        when(assetMapper.selectById("a1")).thenReturn(record);

        AgentAsset result = service.getRecord("user-1", "a1");

        assertEquals("a1", result.getId());
    }

    @Test
    void retryRejectsEditorRecordWithoutConversation() {
        AgentAsset record = asset("video", "failed");
        record.setConversationId(null);
        record.setUserId("user-1");
        record.setProjectId("project-1");
        when(assetMapper.selectById("a1")).thenReturn(record);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.retry("user-1", "a1"));

        assertEquals(40001, ex.getCode());
    }

    private AgentAsset asset(String type, String status) {
        AgentAsset record = new AgentAsset();
        record.setId("a1");
        record.setConversationId("conv-1");
        record.setType(type);
        record.setStatus(status);
        record.setCreatedAt(OffsetDateTime.now().minusSeconds(5));
        return record;
    }
}
