package com.moon.moonagent.ai.storyboard.production;

import com.moon.moonagent.ai.storyboard.StoryboardShot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.Approval;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.AssetRef;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.OptimizationResult;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.OutlinePlan;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.PlotAssessment;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.PseudoShot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.RoleProfile;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.ScriptRepairResult;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.ShotGenerationRequest;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryBible;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardProductionRequest;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardSegment;

import java.util.ArrayList;
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

    /**
     * 阶段 A：极简大纲（一次调用）。
     *
     * <p>默认实现退回 {@link #planPseudoShots} 并把原剧本截断成梗概——这样所有 Fake/降级实现
     * 不需要改动就能跑通「大纲 → 审批 → 补全」的新流程；生产实现（Spring AI）覆盖成专门的
     * 薄输出提示词，避免仍然吐出 13 字段伪分镜。
     */
    default OutlinePlan planOutline(StoryboardProductionRequest request, List<RoleProfile> roles,
                                    List<AssetRef> assets, StoryboardStreamSink sink) {
        StoryboardSegment segment = new StoryboardSegment(
                "outline", 1, "完整剧情", request.originalScript(), "");
        String synopsis = request.originalScript() == null ? "" : request.originalScript().trim();
        if (synopsis.length() > 120) synopsis = synopsis.substring(0, 120);
        return new OutlinePlan(synopsis, StoryBible.empty(),
                planPseudoShots(request, segment, StoryBible.empty(), roles, assets, sink));
    }

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

    /**
     * 批量扩写正式镜头（一次调用出 N 镜）。
     *
     * <p>默认实现逐镜循环——所有 Fake / 降级实现无需改动即可工作；
     * 生产实现（Spring AI）覆盖为真正的批量提示词，把 30 镜的调用次数从 30 降到 5。
     * 返回值可能少于请求数（模型漏镜），调用方必须按缺失记失败，不能假设等长。
     */
    default List<StoryboardShot> generateShots(List<ShotGenerationRequest> requests,
                                               StoryboardStreamSink sink) {
        if (requests == null || requests.isEmpty()) return List.of();
        List<StoryboardShot> shots = new ArrayList<>(requests.size());
        for (ShotGenerationRequest request : requests) {
            shots.add(generateShot(request, sink));
        }
        return List.copyOf(shots);
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
