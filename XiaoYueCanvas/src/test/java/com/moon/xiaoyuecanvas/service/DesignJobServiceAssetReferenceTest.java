package com.moon.xiaoyuecanvas.service;

import com.moon.moonagent.ai.VideoGenerationService;
import com.moon.moonagent.ai.ImageGenerationService;
import com.moon.moonagent.ai.video.ReferenceImage;
import com.moon.moonagent.ai.video.ReferenceImageRole;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.xiaoyuecanvas.entity.CanvasAsset;
import com.moon.xiaoyuecanvas.entity.CanvasJob;
import com.moon.xiaoyuecanvas.entity.CanvasJobEvent;
import com.moon.xiaoyuecanvas.repository.CanvasJobRepository;
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

    @Test
    void passesRedrawMaskAssetToImageEdit() {
        AgentAssetMapper assetMapper = mock(AgentAssetMapper.class);
        CanvasAssetService canvasAssetService = mock(CanvasAssetService.class);
        ImageGenerationService imageGenerationService = mock(ImageGenerationService.class);
        CanvasJobRepository jobRepository = mock(CanvasJobRepository.class);
        CanvasAsset mask = new CanvasAsset();
        mask.setId("mask-1");
        mask.setObjectUrl("/api/files/images/redraw-mask.png");
        CanvasAsset source = new CanvasAsset();
        source.setId("source-1");
        source.setType("image");
        source.setObjectUrl("/api/files/images/source.png");
        when(canvasAssetService.getOwned("user-1", "mask-1")).thenReturn(mask);
        when(canvasAssetService.getOwned("user-1", "source-1")).thenReturn(source);
        when(imageGenerationService.generateImagesWithMask(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(50201, "stop after mask capture"));
        when(jobRepository.updateProgress(any(), any(), any(Integer.class), any(), any()))
                .thenReturn(mock(CanvasJobEvent.class));

        DesignJobService service = new DesignJobService(
                null, jobRepository, assetMapper, canvasAssetService,
                null, null, null, null, null, imageGenerationService, null,
                mock(GenerationRecordWriter.class));
        CanvasJob job = new CanvasJob();
        job.setId("job-image");
        job.setUserId("user-1");
        job.setCapabilityId("image.redraw");
        job.setSourceAssetIds(JsonUtil.toJson(List.of("source-1")));
        job.setParams(JsonUtil.toJson(Map.of(
                "prompt", "change the chair",
                "model", "gpt-image-1",
                "redrawMaskAssetId", "mask-1",
                "referenceAssetIds", List.of("source-1")
        )));

        assertThrows(BusinessException.class, () -> service.handleImage(job, "owner-1"));

        verify(imageGenerationService).generateImagesWithMask(
                any(), eq("change the chair"), eq("gpt-image-1"), any(), any(), any(),
                eq(List.of("/api/files/images/source.png")), any(),
                eq("/api/files/images/redraw-mask.png"), any());
    }

    @Test
    void rejectsMultiAngleRequestsWithoutTheFixedSeedreamContract() {
        AgentAssetMapper assetMapper = mock(AgentAssetMapper.class);
        CanvasAssetService canvasAssetService = mock(CanvasAssetService.class);
        ImageGenerationService imageGenerationService = mock(ImageGenerationService.class);
        CanvasJobRepository jobRepository = mock(CanvasJobRepository.class);
        when(jobRepository.updateProgress(any(), any(), any(Integer.class), any(), any()))
                .thenReturn(mock(CanvasJobEvent.class));
        DesignJobService service = new DesignJobService(
                null, jobRepository, assetMapper, canvasAssetService,
                null, null, null, null, null, imageGenerationService, null,
                mock(GenerationRecordWriter.class));
        CanvasJob job = new CanvasJob();
        job.setId("job-angle");
        job.setUserId("user-1");
        job.setCapabilityId("image.redraw");
        job.setParams(JsonUtil.toJson(Map.of(
                "prompt", "multi-angle",
                "model", "gpt-image-2",
                "size", "2K",
                "n", 4,
                "multiAngleMode", "multi"
        )));

        BusinessException error = assertThrows(
                BusinessException.class, () -> service.handleImage(job, "owner-1"));

        assertEquals(40001, error.getCode());
        verify(imageGenerationService, never()).generateImages(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
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
