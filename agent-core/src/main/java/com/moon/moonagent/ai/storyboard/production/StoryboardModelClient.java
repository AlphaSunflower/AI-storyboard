package com.moon.moonagent.ai.storyboard.production;

import com.moon.moonagent.ai.storyboard.StoryboardShot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.Approval;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.AssetRef;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.OptimizationResult;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.PlotAssessment;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.PseudoShot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.RoleProfile;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.ScriptRepairResult;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.ShotGenerationRequest;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryBible;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardProductionRequest;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardSegment;

import java.util.List;

/**
 * Model boundary for storyboard production. Implementations may use one or more
 * LLM calls; the orchestration service owns ordering, retries and persistence.
 */
public interface StoryboardModelClient {

    PlotAssessment assessPlot(StoryboardProductionRequest request, List<AssetRef> assets);

    OptimizationResult optimizeScript(StoryboardProductionRequest request, PlotAssessment assessment,
                                      List<RoleProfile> roles, List<AssetRef> assets, String feedback);

    ScriptRepairResult repairScript(StoryboardProductionRequest request, PlotAssessment assessment,
                                    List<RoleProfile> roles, List<AssetRef> assets);

    List<StoryboardSegment> segmentStory(String script, StoryBible storyBible);

    List<PseudoShot> planPseudoShots(StoryboardProductionRequest request, StoryboardSegment segment,
                                     StoryBible storyBible, List<RoleProfile> roles, List<AssetRef> assets);

    List<PseudoShot> revisePseudoShots(List<PseudoShot> shots, String instruction, String shotId,
                                       StoryBible storyBible, List<AssetRef> assets);

    StoryboardShot generateShot(ShotGenerationRequest request);

    default OptimizationResult optimizeScript(StoryboardProductionRequest request, PlotAssessment assessment,
                                              List<RoleProfile> roles, List<AssetRef> assets) {
        return optimizeScript(request, assessment, roles, assets, "");
    }
}
