package com.storyboard.asset.worker;

import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.service.AssetAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AssetAnalysisWorker {

    private static final Logger log = LoggerFactory.getLogger(AssetAnalysisWorker.class);

    private final LibraryRepository repository;
    private final AssetAnalysisService analysisService;
    private final String workerId = "asset-analysis-" + UUID.randomUUID();

    public AssetAnalysisWorker(LibraryRepository repository, AssetAnalysisService analysisService) {
        this.repository = repository;
        this.analysisService = analysisService;
    }

    @Scheduled(fixedDelay = 5000)
    public void run() {
        for (LibraryRepository.AnalysisTask task : repository.claimAnalysisTasks(workerId, 2)) {
            try {
                analysisService.analyze(task);
                repository.completeAnalysis(task.id());
            } catch (Exception e) {
                repository.failAnalysis(task.id(), task.attemptCount(), "ANALYSIS_FAILED", trim(e.getMessage()));
                log.warn("资产图片分析失败: taskId={}, attempt={}, error={}",
                        task.id(), task.attemptCount(), e.getMessage());
            }
        }
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) return "分析失败";
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
}
