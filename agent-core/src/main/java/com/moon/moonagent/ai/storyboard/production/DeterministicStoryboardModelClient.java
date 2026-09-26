package com.moon.moonagent.ai.storyboard.production;

import com.moon.moonagent.ai.storyboard.StoryboardShot;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic model implementation for local smoke tests and CI.
 */
@Service
@ConditionalOnProperty(prefix = "ai.storyboard.production-v2", name = "model-mode",
        havingValue = "deterministic")
public class DeterministicStoryboardModelClient implements StoryboardModelClient {

    @Override
    public PlotAssessment assessPlot(StoryboardProductionRequest request, List<AssetRef> assets) {
        boolean complete = request.originalScript().contains("最终") || request.originalScript().contains("结局");
        return new PlotAssessment(
                complete,
                complete ? List.of() : List.of("明确结局"),
                complete ? List.of() : List.of("冲突收束不足"),
                complete ? "剧情结构完整" : "需要补足结局与因果收束",
                complete ? "" : "补足主角选择、代价与最终结果。");
    }

    @Override
    public OptimizationResult optimizeScript(StoryboardProductionRequest request, PlotAssessment assessment,
                                             List<RoleProfile> roles, List<AssetRef> assets, String feedback) {
        String base = request.originalScript();
        return new OptimizationResult(
                base + "\n优化：强化人物动机、因果推进与连续性。",
                base + "\n扩写：补足冲突升级、专业细节、关键选择、代价与结局。",
                new StoryBible(
                        "主角在压力下作出关键选择",
                        List.of("主角", "关键对手"),
                        List.of("相互牵制并逐步揭示真相"),
                        List.of("行为必须符合角色职业规范"),
                        List.of("事件按因果顺序推进"),
                        List.of("服装、道具、时间状态保持一致"),
                        List.of("写实电影感")));
    }

    @Override
    public ScriptRepairResult repairScript(StoryboardProductionRequest request,
                                           PlotAssessment assessment,
                                           List<RoleProfile> roles,
                                           List<AssetRef> assets) {
        OptimizationResult optimized = optimizeScript(
                request, assessment, roles, assets, "");
        return new ScriptRepairResult(
                optimized.optimizedScript(), optimized.storyBible(), List.of());
    }

    @Override
    public List<StoryboardSegment> segmentStory(String script, StoryBible storyBible) {
        if (script == null || script.length() < 6000) {
            return List.of(new StoryboardSegment("segment-1", 1, "完整剧情", script, ""));
        }
        int middle = Math.max(1, script.length() / 2);
        return List.of(
                new StoryboardSegment("segment-1", 1, "前段", script.substring(0, middle), "承接后半段"),
                new StoryboardSegment("segment-2", 2, "后段", script.substring(middle), "承接前半段")
        );
    }

    @Override
    public List<PseudoShot> planPseudoShots(StoryboardProductionRequest request, StoryboardSegment segment,
                                            StoryBible storyBible, List<RoleProfile> roles, List<AssetRef> assets) {
        List<PseudoShot> shots = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            int ordinal = index + 1;
            String shotId = segment.segmentId() + "-shot-" + ordinal;
            List<String> refs = assets.isEmpty() || ordinal == 1
                    ? List.of() : List.of(assets.getFirst().assetId());
            shots.add(new PseudoShot(
                    shotId, segment.segmentId(), ordinal,
                    ordinal == 1 ? "建立冲突与人物目标" : "推进选择并形成转折",
                    List.of("主角"), "主要场景", "连续时间",
                    ordinal == 1 ? "外部阻碍出现" : "选择带来代价",
                    ordinal == 1 ? "主角发现关键线索" : "主角作出不可逆选择",
                    ordinal == 1 ? "警惕" : "决绝",
                    List.of("人物状态连续", "道具位置连续"),
                    List.of(segment.sourceText()), refs));
        }
        return shots;
    }

    @Override
    public List<PseudoShot> revisePseudoShots(List<PseudoShot> shots, String instruction, String shotId,
                                              StoryBible storyBible, List<AssetRef> assets) {
        if (shotId == null || shotId.isBlank()) return shots;
        return shots.stream().map(shot -> shot.shotId().equals(shotId)
                ? new PseudoShot(shot.shotId(), shot.segmentId(), shot.shotOrdinal(),
                shot.narrativePurpose(), shot.characters(), shot.location(), shot.time(),
                shot.conflict(), shot.action() + "；" + instruction, shot.emotion(),
                shot.continuity(), shot.sourceExcerpt(), shot.referenceAssetIds())
                : shot).toList();
    }

    @Override
    public StoryboardShot generateShot(ShotGenerationRequest request) {
        PseudoShot pseudo = request.pseudoShot();
        return new StoryboardShot(
                pseudo.shotId(), pseudo.segmentId(), pseudo.shotOrdinal(),
                pseudo.action(), pseudo.shotOrdinal() == 1 ? "中景" : "近景",
                pseudo.shotOrdinal() == 1 ? "缓慢推近" : "轻微横移", 3,
                pseudo.location() + "，" + pseudo.characters() + "，" + pseudo.action(),
                "【核心创意】" + pseudo.action() + "；【画面过程说明】" + pseudo.emotion()
                        + "；非叙事性音乐：N/A",
                "模糊,畸变,多余文字,水印,形变,低清晰度",
                pseudo.referenceAssetIds());
    }
}
