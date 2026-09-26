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

    StoryboardRunSnapshot startAutoStoryboard(StoryboardProductionRequest request,
                                              StoryboardProgressListener progressListener);

    StoryboardRunSnapshot get(String runId);

    StoryboardRunSnapshot getOwned(String runId, String userId, String projectId);

    StoryboardRunSnapshot getLatestOwned(String userId, String projectId, String conversationId);

    StoryboardRunSnapshot revise(String runId, Stage targetStage, String instruction, String shotId);

    StoryboardRunSnapshot approve(String runId, Approval approval);

    StoryboardRunSnapshot approveToFinal(String runId, Approval plotApproval);

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

    ShotReferenceResolution referenceAssetIds(String runId, String shotId);

    String projectSceneId(String runId, String shotId);

    void cancel(String runId);
}
