package com.storyboard.asset.worker;

import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetStatus;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.service.AssetAnalysisService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetAnalysisWorkerTest {

    @Test
    void writesLocalMetadataAfterFinalAnalysisFailure() throws Exception {
        LibraryRepository repository = mock(LibraryRepository.class);
        AssetAnalysisService analysisService = mock(AssetAnalysisService.class);
        AssetAnalysisWorker worker = new AssetAnalysisWorker(repository, analysisService);
        LibraryRepository.AnalysisTask task = new LibraryRepository.AnalysisTask("task-1", "asset-1", 3);
        when(repository.claimAnalysisTasks(anyString(), eq(2))).thenReturn(List.of(task));
        when(repository.findAssetInternal("asset-1")).thenReturn(Optional.of(asset()));
        org.mockito.Mockito.doThrow(new RuntimeException("gateway unavailable"))
                .when(analysisService).analyze(task);

        worker.run();

        verify(repository).markAiMetadata(eq("asset-1"), anyString(), eq(List.of("图片", "待分析")));
        verify(repository).completeAnalysis("task-1");
        verify(repository, never()).failAnalysis(anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void retriesBeforeFallbackLimit() throws Exception {
        LibraryRepository repository = mock(LibraryRepository.class);
        AssetAnalysisService analysisService = mock(AssetAnalysisService.class);
        AssetAnalysisWorker worker = new AssetAnalysisWorker(repository, analysisService);
        LibraryRepository.AnalysisTask task = new LibraryRepository.AnalysisTask("task-1", "asset-1", 1);
        when(repository.claimAnalysisTasks(anyString(), eq(2))).thenReturn(List.of(task));
        org.mockito.Mockito.doThrow(new RuntimeException("gateway unavailable"))
                .when(analysisService).analyze(task);

        worker.run();

        verify(repository).failAnalysis("task-1", 1, "ANALYSIS_FAILED", "gateway unavailable");
        verify(repository, never()).markAiMetadata(anyString(), anyString(), any());
    }

    private static AssetSummary asset() {
        return new AssetSummary(
                "asset-1",
                "u1",
                AssetScope.PROJECT,
                "p1",
                AssetType.IMAGE,
                null,
                "角色参考",
                null,
                null,
                false,
                com.storyboard.asset.client.AssetContracts.MetadataState.NONE,
                AssetStatus.ACTIVE,
                null,
                1,
                List.of(),
                List.of(),
                "upload",
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }
}
