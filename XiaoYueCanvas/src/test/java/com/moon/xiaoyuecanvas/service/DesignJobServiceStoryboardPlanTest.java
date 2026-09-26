package com.moon.xiaoyuecanvas.service;

import com.moon.moonagent.ai.storyboard.StoryboardPlanningService;
import com.moon.moonagent.ai.storyboard.StoryboardShot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionService;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.Approval;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.PlotAssessment;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.PseudoShot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.Stage;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryBible;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardRunSnapshot;
import com.moon.moonagent.entity.AgentAsset;
import com.moon.moonagent.mapper.AgentAssetMapper;
import com.moon.xiaoyuecanvas.entity.CanvasJob;
import com.moon.xiaoyuecanvas.repository.CanvasJobRepository;
import com.moon.xiaoyuecanvas.util.JsonUtil;
import com.storyboard.common.BusinessException;
import com.storyboard.common.generation.GenerationRecord;
import com.storyboard.common.generation.GenerationRecordTypes;
import com.storyboard.common.generation.GenerationRecordWriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesignJobServiceStoryboardPlanTest {

    @Test
    void publishesStoryboardCreatedAndPersistsInlineAuditRecord() {
        CanvasJobRepository repository = mock(CanvasJobRepository.class);
        AgentAssetMapper assetMapper = mock(AgentAssetMapper.class);
        GenerationRecordWriter writer = mock(GenerationRecordWriter.class);
        StoryboardPlanningService planner = mock(StoryboardPlanningService.class);
        AgentAsset source = new AgentAsset();
        source.setId("source-1");
        source.setType("image");
        source.setUrl("/api/files/images/source.png");
        when(assetMapper.selectById("source-1")).thenReturn(source);
        when(planner.plan("script text")).thenReturn(List.of(shot()));
        when(writer.create(any(GenerationRecord.class))).thenReturn("plan-record-1");

        DesignJobService service = service(repository, assetMapper, writer, planner);
        CanvasJob job = job();

        service.handleStoryboardPlan(job);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(repository).appendEvent(eq("job-1"), eq("storyboard.created"), payloadCaptor.capture());
        assertEquals("job-1", payloadCaptor.getValue().get("jobId"));
        assertEquals(StoryboardPlanningService.PLAN_VERSION, payloadCaptor.getValue().get("planVersion"));
        assertEquals(List.of(shot()), payloadCaptor.getValue().get("shots"));

        ArgumentCaptor<GenerationRecord> recordCaptor = ArgumentCaptor.forClass(GenerationRecord.class);
        verify(writer).create(recordCaptor.capture());
        GenerationRecord record = recordCaptor.getValue();
        assertEquals(GenerationRecordTypes.STORYBOARD_PLAN, record.type());
        assertEquals(GenerationRecordTypes.STORYBOARD_PLAN, record.genType());
        assertEquals(GenerationRecordTypes.COMPLETED, record.status());
        assertEquals("script text", record.prompt());
        assertNull(record.url());
        assertTrue(record.resultUrls().isEmpty());
        assertEquals(1, record.referenceUrls().size());
        assertEquals("image", record.referenceUrls().getFirst().type());
        assertEquals(StoryboardPlanningService.PLAN_VERSION, record.params().get("planVersion"));
        assertEquals(List.of(shot()), record.params().get("shots"));
        assertTrue(record.durationMs() >= 0);
    }

    @Test
    void persistsFailedPlanningAttemptAndDoesNotPublishStoryboardEvent() {
        CanvasJobRepository repository = mock(CanvasJobRepository.class);
        AgentAssetMapper assetMapper = mock(AgentAssetMapper.class);
        GenerationRecordWriter writer = mock(GenerationRecordWriter.class);
        StoryboardPlanningService planner = mock(StoryboardPlanningService.class);
        when(planner.plan("script text")).thenThrow(new BusinessException(50201, "invalid JSON"));
        when(writer.create(any(GenerationRecord.class))).thenReturn("plan-record-1");

        DesignJobService service = service(repository, assetMapper, writer, planner);

        assertThrows(BusinessException.class, () -> service.handleStoryboardPlan(job()));

        ArgumentCaptor<GenerationRecord> recordCaptor = ArgumentCaptor.forClass(GenerationRecord.class);
        verify(writer).create(recordCaptor.capture());
        GenerationRecord record = recordCaptor.getValue();
        assertEquals(GenerationRecordTypes.STORYBOARD_PLAN, record.genType());
        assertEquals(GenerationRecordTypes.FAILED, record.status());
        assertEquals("invalid JSON", record.error());
        assertTrue(record.resultUrls().isEmpty());
        verify(repository, never()).appendEvent(eq("job-1"), eq("storyboard.created"), anyMap());
    }

    @Test
    void capabilityListIncludesStoryboardPlan() {
        DesignJobService service = service(
                mock(CanvasJobRepository.class), mock(AgentAssetMapper.class),
                mock(GenerationRecordWriter.class), mock(StoryboardPlanningService.class));

        assertTrue(service.capabilities().stream()
                .anyMatch(capability -> "storyboard.plan".equals(capability.id())));
        assertTrue(service.capabilities().stream()
                .anyMatch(capability -> "storyboard.outline".equals(capability.id())));
        assertTrue(service.capabilities().stream()
                .anyMatch(capability -> "storyboard.finalize".equals(capability.id())));
    }

    @Test
    void stagedOutlinePublishesReviewWithoutCreatingFinalShots() {
        CanvasJobRepository repository = mock(CanvasJobRepository.class);
        StoryboardProductionService production = mock(StoryboardProductionService.class);
        StoryboardRunSnapshot snapshot = new StoryboardRunSnapshot(
                "run-1", Stage.PLOT_REVIEW, 1, "script text", "optimized script",
                "expanded script",
                new PlotAssessment(false, List.of("结局"), List.of(), "missing ending", "补充结局"),
                StoryBible.empty(), List.of(), List.of(),
                List.of(new PseudoShot("shot-1", "segment-1", 1, "发现", List.of(),
                        "医院", "夜", "冲突", "行动", "紧张", List.of(), List.of(), List.of())),
                List.of(), "");
        when(production.start(any())).thenReturn(snapshot);

        DesignJobService service = new DesignJobService(
                null, repository, mock(AgentAssetMapper.class), null, null, null,
                null, mock(StoryboardPlanningService.class), production, null, null, null,
                mock(GenerationRecordWriter.class));
        CanvasJob job = job();
        job.setCapabilityId("storyboard.outline");

        service.handleStoryboardOutline(job);

        verify(repository).appendEvent(eq("job-1"), eq("storyboard.outline.ready"), anyMap());
        verify(repository).appendEvent(eq("job-1"), eq("storyboard.preview"), anyMap());
        verify(repository, never()).appendEvent(eq("job-1"), eq("storyboard.created"), anyMap());
    }

    @Test
    void finalizePublishesCreatedOnlyAfterPseudoApproval() {
        CanvasJobRepository repository = mock(CanvasJobRepository.class);
        StoryboardProductionService production = mock(StoryboardProductionService.class);
        StoryboardRunSnapshot snapshot = new StoryboardRunSnapshot(
                "run-1", Stage.COMPLETED, 3, "script text", "optimized script",
                "",
                new PlotAssessment(true, List.of(), List.of(), "", ""),
                StoryBible.empty(), List.of(), List.of(), List.of(), List.of(shot()), "");
        StoryboardRunSnapshot current = new StoryboardRunSnapshot(
                "run-1", Stage.PSEUDO_REVIEW, 2, "script text", "optimized script",
                "", new PlotAssessment(true, List.of(), List.of(), "", ""),
                StoryBible.empty(), List.of(), List.of(),
                List.of(new PseudoShot("shot-1", "segment-1", 1, "目的", List.of(),
                        "地点", "时间", "冲突", "行动", "情绪", List.of(), List.of(), List.of())),
                List.of(), "");
        when(production.getOwned("run-1", "user-1", "project-1")).thenReturn(current);
        when(production.approve(eq("run-1"), any(Approval.class))).thenReturn(snapshot);

        DesignJobService service = new DesignJobService(
                null, repository, mock(AgentAssetMapper.class), null, null, null,
                null, mock(StoryboardPlanningService.class), production, null, null, null,
                mock(GenerationRecordWriter.class));
        CanvasJob job = job();
        job.setCapabilityId("storyboard.finalize");
        job.setParams(JsonUtil.toJson(Map.of("prompt", "script text", "runId", "run-1")));

        service.handleStoryboardFinalize(job);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(repository).appendEvent(eq("job-1"), eq("storyboard.created"), payloadCaptor.capture());
        assertEquals("run-1", payloadCaptor.getValue().get("runId"));
        assertEquals(List.of(shot()), payloadCaptor.getValue().get("shots"));
    }

    @Test
    void passesReferenceImagesToTheStoryboardPlanner() {
        CanvasJobRepository repository = mock(CanvasJobRepository.class);
        AgentAssetMapper assetMapper = mock(AgentAssetMapper.class);
        GenerationRecordWriter writer = mock(GenerationRecordWriter.class);
        StoryboardPlanningService planner = mock(StoryboardPlanningService.class);
        List<String> referenceImages = List.of("data:image/png;base64,AAAA");
        when(planner.plan("script text", referenceImages)).thenReturn(List.of(shot()));
        when(writer.create(any(GenerationRecord.class))).thenReturn("plan-record-1");

        CanvasJob job = job();
        job.setParams(JsonUtil.toJson(Map.of(
                "prompt", "script text",
                "referenceImages", referenceImages,
                "canvas", Map.of("canvasId", "canvas-1"))));

        service(repository, assetMapper, writer, planner).handleStoryboardPlan(job);

        verify(planner).plan("script text", referenceImages);
    }

    private static DesignJobService service(CanvasJobRepository repository,
                                            AgentAssetMapper assetMapper,
                                            GenerationRecordWriter writer,
                                            StoryboardPlanningService planner) {
        return new DesignJobService(
                null, repository, assetMapper, null, null, null, null, planner, mock(StoryboardProductionService.class),
                null, null, null, writer);
    }

    private static CanvasJob job() {
        CanvasJob job = new CanvasJob();
        job.setId("job-1");
        job.setUserId("user-1");
        job.setProjectId("project-1");
        job.setConversationId("conversation-1");
        job.setCapabilityId("storyboard.plan");
        job.setSourceAssetIds(JsonUtil.toJson(List.of("source-1")));
        job.setParams(JsonUtil.toJson(Map.of(
                "prompt", "script text",
                "model", "text-model",
                "canvas", Map.of("canvasId", "canvas-1"))));
        return job;
    }

    private static StoryboardShot shot() {
        return new StoryboardShot(
                "shot-1", "segment-1", 1, "A door opens", "wide", "push in",
                3, "door opening", "camera pushes through the door", "blur", List.of());
    }
}
