package com.storyboard.asset.worker;

import com.storyboard.asset.repository.LibraryRepository;
import com.storyboard.asset.service.AssetAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class AssetAnalysisWorker {

    private static final Logger log = LoggerFactory.getLogger(AssetAnalysisWorker.class);
    private static final int MAX_ANALYSIS_ATTEMPTS = 3;

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
                if (task.attemptCount() >= MAX_ANALYSIS_ATTEMPTS && applyFallback(task)) {
                    log.warn("资产图片分析不可用，已写入本地兜底元数据: taskId={}, attempt={}, error={}",
                            task.id(), task.attemptCount(), e.getMessage());
                    continue;
                }
                repository.failAnalysis(task.id(), task.attemptCount(), "ANALYSIS_FAILED", trim(e.getMessage()));
                log.warn("资产图片分析失败: taskId={}, attempt={}, error={}",
                        task.id(), task.attemptCount(), e.getMessage());
            }
        }
    }

    private boolean applyFallback(LibraryRepository.AnalysisTask task) {
        try {
            var asset = repository.findAssetInternal(task.assetId()).orElse(null);
            if (asset == null) return false;
            String name = asset.name() == null || asset.name().isBlank() ? "未命名素材" : asset.name();
            List<String> tags = new ArrayList<>();
            tags.add(typeLabel(asset.type()));
            tags.add("待分析");
            repository.markAiMetadata(
                    task.assetId(),
                    "「" + name + "」已保存到项目库。AI 分析暂不可用，可稍后手动补充描述与标签。",
                    List.copyOf(tags)
            );
            repository.completeAnalysis(task.id());
            return true;
        } catch (Exception fallbackError) {
            log.warn("写入资产兜底元数据失败: taskId={}, error={}", task.id(), fallbackError.getMessage());
            return false;
        }
    }

    private static String typeLabel(com.storyboard.asset.client.AssetContracts.AssetType type) {
        if (type == null) return "素材";
        return switch (type) {
            case IMAGE -> "图片";
            case VIDEO -> "视频";
            case AUDIO -> "音频";
            case DOCUMENT -> "文档";
            case OTHER -> "其他";
            case AI_ASSET -> "AI资产";
            case GROUP -> "资产组";
        };
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) return "分析失败";
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
}
