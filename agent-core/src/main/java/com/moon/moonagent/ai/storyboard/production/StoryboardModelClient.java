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

    default PlotAssessment assessPlot(StoryboardProductionRequest request, List<AssetRef> assets,
                                      StoryboardStreamSink sink) {
        return assessPlot(request, assets);
    }

    OptimizationResult optimizeScript(StoryboardProductionRequest request, PlotAssessment assessment,
                                      List<RoleProfile> roles, List<AssetRef> assets, String feedback);

    ScriptRepairResult repairScript(StoryboardProductionRequest request, PlotAssessment assessment,
                                    List<RoleProfile> roles, List<AssetRef> assets);

    default ScriptRepairResult repairScript(StoryboardProductionRequest request,
                                            PlotAssessment assessment,
                                            List<RoleProfile> roles,
                                            List<AssetRef> assets,
                                            StoryboardStreamSink sink) {
        return repairScript(request, assessment, roles, assets);
    }

    List<StoryboardSegment> segmentStory(String script, StoryBible storyBible);

    default List<StoryboardSegment> segmentStory(String script, StoryBible storyBible,
                                                 StoryboardStreamSink sink) {
        return segmentStory(script, storyBible);
    }

    List<PseudoShot> planPseudoShots(StoryboardProductionRequest request, StoryboardSegment segment,
                                     StoryBible storyBible, List<RoleProfile> roles, List<AssetRef> assets);

    default List<PseudoShot> planPseudoShots(StoryboardProductionRequest request,
                                             StoryboardSegment segment,
                                             StoryBible storyBible,
                                             List<RoleProfile> roles,
                                             List<AssetRef> assets,
                                             StoryboardStreamSink sink) {
        return planPseudoShots(request, segment, storyBible, roles, assets);
    }

    List<PseudoShot> revisePseudoShots(List<PseudoShot> shots, String instruction, String shotId,
                                       StoryBible storyBible, List<AssetRef> assets);

    default List<PseudoShot> revisePseudoShots(List<PseudoShot> shots, String instruction,
                                               String shotId, StoryBible storyBible,
                                               List<AssetRef> assets, StoryboardStreamSink sink) {
        return revisePseudoShots(shots, instruction, shotId, storyBible, assets);
    }

    StoryboardShot generateShot(ShotGenerationRequest request);

    default StoryboardShot generateShot(ShotGenerationRequest request, StoryboardStreamSink sink) {
        return generateShot(request);
    }

    default OptimizationResult optimizeScript(StoryboardProductionRequest request, PlotAssessment assessment,
                                              List<RoleProfile> roles, List<AssetRef> assets) {
        return optimizeScript(request, assessment, roles, assets, "");
    }

    /**
     * 流式版本：把 optimizedScript 的增量推给 sink（实现不支持时退化为普通调用）。
     */
    default OptimizationResult optimizeScript(StoryboardProductionRequest request, PlotAssessment assessment,
                                              List<RoleProfile> roles, List<AssetRef> assets, String feedback,
                                              StoryboardStreamSink sink) {
        return optimizeScript(request, assessment, roles, assets, feedback);
    }
}
