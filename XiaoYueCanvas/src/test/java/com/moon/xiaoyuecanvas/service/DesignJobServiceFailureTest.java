package com.moon.xiaoyuecanvas.service;

import com.moon.moonagent.ai.VideoGenerationService;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.xiaoyuecanvas.entity.CanvasJob;
import com.moon.xiaoyuecanvas.mapper.CanvasJobMapper;
import com.moon.xiaoyuecanvas.repository.CanvasJobRepository;
import com.moon.xiaoyuecanvas.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesignJobServiceFailureTest {

    @Test
    void statusIncludesCompletedResultAssets() {
        CanvasJobMapper jobMapper = mock(CanvasJobMapper.class);
        AgentAssetMapper assetMapper = mock(AgentAssetMapper.class);
        CanvasJob job = new CanvasJob();
        job.setId("job-1");
        job.setUserId("user-1");
        job.setStatus("completed");
        job.setProgress(100);
        job.setResultAssetId("asset-1");
        job.setResultAssetIds(JsonUtil.toJson(List.of("asset-1")));
        AgentAsset asset = new AgentAsset();
        asset.setId("asset-1");
        asset.setType("video");
        asset.setUrl("/api/files/videos/generated.mp4");
        when(jobMapper.selectById("job-1")).thenReturn(job);
        when(assetMapper.selectById("asset-1")).thenReturn(asset);

        DesignJobService service = new DesignJobService(
                jobMapper, null, assetMapper, null, null, null, null, null,
                null, null, null, null);

        Map<String, Object> status = service.status("user-1", "job-1");

        assertEquals(List.of(Map.of(
                "id", "asset-1",
                "name", "generated.mp4",
                "mimeType", "video/mp4"
        )), status.get("assets"));
    }

    @Test
    void linkageErrorMarksJobFailedAndRethrows() {
        CanvasJobRepository repository = mock(CanvasJobRepository.class);
        AgentAssetMapper assetMapper = mock(AgentAssetMapper.class);
        VideoGenerationService videoGenerationService = mock(VideoGenerationService.class);
        when(videoGenerationService.createVideoTask(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), eq(true)))
                .thenThrow(new LinkageError("ReferenceImage missing"));
        when(repository.fail(eq("job-1"), eq("owner-1"), any(), any())).thenReturn(null);

        DesignJobService service = new DesignJobService(
                null, repository, assetMapper, null, null, null, null, null,
                null, null, videoGenerationService, null);
        CanvasJob job = new CanvasJob();
        job.setId("job-1");
        job.setCapabilityId("video.generate");
        job.setParams(JsonUtil.toJson(Map.of("prompt", "generate a video")));

        assertThrows(LinkageError.class, () -> service.runJob(job, "owner-1"));

        verify(repository).fail(
                eq("job-1"),
                eq("owner-1"),
                contains("ReferenceImage missing"),
                any());
    }
}
