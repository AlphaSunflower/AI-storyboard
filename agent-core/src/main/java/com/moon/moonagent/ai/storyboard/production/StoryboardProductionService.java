package com.moon.moonagent.ai.storyboard.production;

import com.moon.moonagent.ai.storyboard.StoryboardShot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.Approval;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.PseudoShot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.ShotReferenceResolution;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.Stage;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardProductionRequest;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardRunSnapshot;

import java.util.List;
import java.util.Map;

public interface StoryboardProductionService {

    StoryboardRunSnapshot start(StoryboardProductionRequest request);

    /** 带进度/流式回调的启动（默认忽略回调，兼容旧实现）。 */
    default StoryboardRunSnapshot start(StoryboardProductionRequest request,
                                       StoryboardProgressListener listener) {
        return start(request);
    }

    StoryboardRunSnapshot startAutoStoryboard(StoryboardProductionRequest request,
                                              StoryboardProgressListener progressListener);

    StoryboardRunSnapshot get(String runId);

    StoryboardRunSnapshot getOwned(String runId, String userId, String projectId);

    StoryboardRunSnapshot getLatestOwned(String userId, String projectId, String conversationId);

    StoryboardRunSnapshot revise(String runId, Stage targetStage, String instruction, String shotId);

    default StoryboardRunSnapshot revise(String runId, Stage targetStage, String instruction, String shotId,
                                        StoryboardProgressListener listener) {
        return revise(runId, targetStage, instruction, shotId);
    }

    StoryboardRunSnapshot approve(String runId, Approval approval);

    /** 带过程回调的确认（默认忽略回调，兼容旧实现）。 */
    default StoryboardRunSnapshot approve(String runId, Approval approval,
                                         StoryboardProgressListener listener) {
        return approve(runId, approval);
    }

    StoryboardRunSnapshot approveToFinal(String runId, Approval plotApproval);

    default StoryboardRunSnapshot approveToFinal(String runId, Approval plotApproval,
                                                StoryboardProgressListener listener) {
        return approveToFinal(runId, plotApproval);
    }

    StoryboardRunSnapshot replaceDraft(
            String runId,
            String userId,
            String projectId,
            int expectedVersion,
            List<PseudoShot> pseudoShots,
            List<StoryboardShot> finalShots);

    StoryboardRunSnapshot markCompleted(
            String runId,
            String userId,
            String projectId,
            Map<String, String> sceneIdsByShotId);

    StoryboardRunSnapshot upsertShot(String runId, PseudoShot shot);

    StoryboardShot retryShot(String runId, String shotId);

    StoryboardRunSnapshot retryIncompleteFinalShots(String runId);

    default StoryboardRunSnapshot retryIncompleteFinalShots(String runId,
                                                           StoryboardProgressListener listener) {
        return retryIncompleteFinalShots(runId);
    }

    /** 该运行已写入项目的分镜 sceneId（去重，按写入顺序）。 */
    List<String> publishedSceneIds(String runId);

    ShotReferenceResolution referenceAssetIds(String runId, String shotId);

    String projectSceneId(String runId, String shotId);

    void cancel(String runId);
}
