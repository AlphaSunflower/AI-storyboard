package com.moon.xiaoyuecanvas.service;

import com.moon.moonagent.ai.VideoGenerationService;
import com.moon.moonagent.ai.video.ReferenceImage;
import com.moon.moonagent.ai.video.ReferenceImageRole;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.xiaoyuecanvas.entity.CanvasAsset;
import com.moon.xiaoyuecanvas.entity.CanvasJob;
import com.moon.xiaoyuecanvas.util.JsonUtil;
import com.storyboard.common.BusinessException;
import com.storyboard.common.generation.GenerationRecordWriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesignJobServiceAssetReferenceTest {

    @Test
    void resolvesReferenceAssetIdsToMinioUrls() {
        CanvasAssetService canvasAssetService = mock(CanvasAssetService.class);
        VideoGenerationService videoGenerationService = mock(VideoGenerationService.class);
        CanvasAsset source = new CanvasAsset();
        source.setId("asset-1");
        source.setObjectUrl("/api/files/images/source.png");
        when(canvasAssetService.getOwned("user-1", "asset-1")).thenReturn(source);
        when(videoGenerationService.createVideoTask(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), eq(true)))
                .thenThrow(new BusinessException(50201, "stop after reference capture"));

        DesignJobService service = service(canvasAssetService, videoGenerationService);
        CanvasJob job = videoJob(Map.of(
                "prompt", "animate this image",
                "referenceAssetIds", List.of("asset-1"),
                "referenceRoles", List.of(Map.of("assetId", "asset-1", "role", "FIRST_FRAME"))
        ));

        assertThrows(BusinessException.class, () -> service.handleVideo(job, "owner-1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReferenceImage>> captor = ArgumentCaptor.forClass(List.class);
        verify(videoGenerationService).createVideoTask(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                captor.capture(), any(), any(), any(), eq(true));
        assertEquals("/api/files/images/source.png", captor.getValue().getFirst().url());
        assertEquals(ReferenceImageRole.FIRST_FRAME, captor.getValue().getFirst().role());
    }

    @Test
    void rejectsLegacyDataUriReference() {
        CanvasAssetService canvasAssetService = mock(CanvasAssetService.class);
        VideoGenerationService videoGenerationService = mock(VideoGenerationService.class);
        DesignJobService service = service(canvasAssetService, videoGenerationService);
        CanvasJob job = videoJob(Map.of(
                "prompt", "animate this image",
                "referenceImages", List.of("data:image/png;base64,AQID")
        ));

        BusinessException error = assertThrows(
                BusinessException.class, () -> service.handleVideo(job, "owner-1"));

        assertEquals(40001, error.getCode());
        verify(videoGenerationService, never()).createVideoTask(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), eq(true));
    }

    private static DesignJobService service(CanvasAssetService canvasAssetService,
                                            VideoGenerationService videoGenerationService) {
        return new DesignJobService(
                null, null, mock(AgentAssetMapper.class), canvasAssetService,
                null, null, null, null, null, null, videoGenerationService,
                mock(GenerationRecordWriter.class));
    }

    private static CanvasJob videoJob(Map<String, Object> params) {
        CanvasJob job = new CanvasJob();
        job.setId("job-1");
        job.setUserId("user-1");
        job.setCapabilityId("video.generate");
        job.setParams(JsonUtil.toJson(params));
        return job;
    }
}
