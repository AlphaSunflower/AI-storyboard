package com.moon.moonagent.ai.storyboard.production;

import com.moon.moonagent.ai.AgentAiConfigProperties;
import com.moon.moonagent.ai.storyboard.StoryboardShot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.Approval;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.AssetRef;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.OptimizationResult;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.Phase;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.PlotAssessment;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.PseudoShot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.RoleKind;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.RoleProfile;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.RunState;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.ScriptChange;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.ScriptRepairResult;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.ShotGenerationRequest;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.ShotRecord;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.Stage;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryBible;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardProductionRequest;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardRunSnapshot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardSegment;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoryboardProductionServiceImplTest {

    @Test
    void autoStoryboardRepairsAndGeneratesFinalReviewWithoutIntermediateApproval() {
        Fixture fixture = fixture();
        List<String> progress = new ArrayList<>();
        AtomicReference<String> plotReady = new AtomicReference<>();

        StoryboardRunSnapshot snapshot = fixture.service.startAutoStoryboard(
                request("完整剧情：主角发现真相并作出选择。"),
                new StoryboardProgressListener() {
                    @Override
                    public void update(String stage, int value) {
                        progress.add(stage + ":" + value);
                    }

                    @Override
                    public void plotReady(String script) {
                        plotReady.set(script);
                    }
                });

        assertThat(snapshot.stage()).isEqualTo(Stage.FINAL_REVIEW);
        assertThat(snapshot.selectedScript()).isEqualTo("修正后的完整剧情");
        assertThat(snapshot.expansionScript()).isEmpty();
        assertThat(snapshot.pseudoShots()).hasSize(2);
        assertThat(snapshot.finalShots()).hasSize(2);
        assertThat(fixture.model.optimizeCalls).hasValue(1);
        assertThat(fixture.model.repairCalls).hasValue(1);
        assertThat(fixture.model.finalCalls).hasValue(2);
        assertThat(progress).contains(
                "script_optimization:8",
                "plot_diagnosis:25",
                "script_repair:40",
                "story_segmentation:55",
                "pseudo_planning:65",
                "final_generation:78");
        assertThat(fixture.model.lastAssessedScript).isEqualTo("扩写后的完整剧情");
        assertThat(fixture.model.lastRepairScript).isEqualTo("扩写后的完整剧情");
        assertThat(plotReady).hasValue("扩写后的完整剧情");
    }

    @Test
    void autoStoryboardSkipsRepairWhenPlotIsComplete() {
        Fixture fixture = fixture();
        fixture.model.assessmentComplete.set(true);

        StoryboardRunSnapshot snapshot = fixture.service.startAutoStoryboard(
                request("完整剧情：主角发现真相并作出选择。"), (stage, value) -> {
                });

        assertThat(snapshot.stage()).isEqualTo(Stage.FINAL_REVIEW);
        assertThat(snapshot.selectedScript())
                .isEqualTo("扩写后的完整剧情");
        assertThat(snapshot.expansionScript()).isEmpty();
        assertThat(fixture.model.optimizeCalls).hasValue(1);
        assertThat(fixture.model.repairCalls).hasValue(0);
        assertThat(snapshot.finalShots()).hasSize(2);
    }

    @Test
    void requiresPlotReviewBeforePseudoReview() {
        Fixture fixture = fixture();
        StoryboardRunSnapshot snapshot = fixture.service.start(request("完整剧情：主角发现真相并作出选择。"));

        assertThat(snapshot.stage()).isEqualTo(Stage.PLOT_REVIEW);
        assertThat(snapshot.selectedScript()).contains("优化");
        assertThat(snapshot.roles()).extracting(RoleProfile::id).contains("screenwriter", "director");
        assertThatThrownBy(() -> fixture.service.approve(snapshot.runId(),
                new Approval(Stage.PSEUDO_REVIEW, "APPROVE", "", List.of(), List.of())))
                .hasMessageContaining("阶段已变化");
    }

    @Test
    void approvesPlotThenFinalizesEveryPseudoShotThroughSingleShotRequests() {
        Fixture fixture = fixture();
        StoryboardRunSnapshot plot = fixture.service.start(request("完整剧情：主角发现真相并作出选择。"));
        StoryboardRunSnapshot pseudo = fixture.service.approve(plot.runId(),
                new Approval(Stage.PLOT_REVIEW, "APPLY_EXPANSION", "", List.of(), List.of()));

        assertThat(pseudo.stage()).isEqualTo(Stage.PSEUDO_REVIEW);
        assertThat(pseudo.pseudoShots()).hasSize(2);
        assertThat(pseudo.finalShots()).isEmpty();
        assertThat(fixture.model.finalCalls).hasValue(0);
        assertThat(fixture.model.lastSegmentScript).isEqualTo("扩写后的完整剧情");

        StoryboardRunSnapshot finalReview = fixture.service.approve(pseudo.runId(),
                new Approval(Stage.PSEUDO_REVIEW, "APPROVE", "", List.of(), List.of()));

        assertThat(finalReview.stage()).isEqualTo(Stage.FINAL_REVIEW);
        assertThat(finalReview.finalShots()).hasSize(2);
        assertThat(fixture.model.finalCalls).hasValue(2);
        assertThat(fixture.store.listShots(finalReview.runId(), Phase.FINAL)).hasSize(2);

        StoryboardRunSnapshot completed = fixture.service.markCompleted(
                finalReview.runId(), "user-1", "project-1",
                Map.of("shot-1", "scene-1", "shot-2", "scene-2"));

        assertThat(completed.stage()).isEqualTo(Stage.COMPLETED);
        assertThat(fixture.service.projectSceneId(finalReview.runId(), "shot-1"))
                .isEqualTo("scene-1");
    }

    @Test
    void approvesPlotDirectlyIntoFormalReviewWithoutPseudoReviewGate() {
        Fixture fixture = fixture();
        StoryboardRunSnapshot plot = fixture.service.start(request("完整剧情：主角发现真相并作出选择。"));

        StoryboardRunSnapshot review = fixture.service.approveToFinal(
                plot.runId(),
                new Approval(Stage.PLOT_REVIEW, "APPLY_EXPANSION", "", List.of(), List.of()));

        assertThat(review.stage()).isEqualTo(Stage.FINAL_REVIEW);
        assertThat(review.finalShots()).hasSize(2);
        assertThat(fixture.model.finalCalls).hasValue(2);
    }

    @Test
    void partialFinalFailureKeepsSuccessfulShotsAndRetriesOnlyRequestedShot() {
        Fixture fixture = fixture();
        fixture.model.failShotId.set("shot-2");
        StoryboardRunSnapshot plot = fixture.service.start(request("完整剧情：主角发现真相并作出选择。"));
        StoryboardRunSnapshot pseudo = fixture.service.approve(plot.runId(),
                new Approval(Stage.PLOT_REVIEW, "APPLY_EXPANSION", "", List.of(), List.of()));
        StoryboardRunSnapshot partial = fixture.service.approve(pseudo.runId(),
                new Approval(Stage.PSEUDO_REVIEW, "APPROVE", "", List.of(), List.of()));

        assertThat(partial.stage()).isEqualTo(Stage.FINALIZING);
        assertThat(partial.finalShots()).extracting(StoryboardShot::shotId).containsExactly("shot-1");
        assertThat(partial.error()).contains("shot-2");

        fixture.model.failShotId.set("");
        StoryboardShot retried = fixture.service.retryShot(partial.runId(), "shot-2");

        assertThat(retried.shotId()).isEqualTo("shot-2");
        assertThat(fixture.service.get(partial.runId()).stage()).isEqualTo(Stage.FINAL_REVIEW);
    }

    @Test
    void upsertShotAcceptsExactlyOneStableShotAndClearsFinalOutputs() {
        Fixture fixture = fixture();
        StoryboardRunSnapshot plot = fixture.service.start(request("完整剧情：主角发现真相并作出选择。"));
        StoryboardRunSnapshot pseudo = fixture.service.approve(plot.runId(),
                new Approval(Stage.PLOT_REVIEW, "APPLY_EXPANSION", "", List.of(), List.of()));
        PseudoShot edited = new PseudoShot("shot-1", "segment-1", 99, "修订目的",
                List.of("主角"), "医院", "夜", "冲突", "行动", "克制",
                List.of(), List.of(), List.of("asset-1"));

        StoryboardRunSnapshot updated = fixture.service.upsertShot(pseudo.runId(), edited);

        assertThat(updated.finalShots()).isEmpty();
        assertThat(updated.pseudoShots()).hasSize(2);
        assertThat(updated.pseudoShots()).filteredOn(shot -> shot.shotId().equals("shot-1"))
                .singleElement().extracting(PseudoShot::referenceAssetIds)
                .isEqualTo(List.of("asset-1"));
    }

    @Test
    void referenceAssetIdsPreferFinalRecordOverPseudoRecord() {
        Fixture fixture = fixture();
        StoryboardRunSnapshot plot = fixture.service.start(request("完整剧情：主角发现真相并作出选择。"));
        StoryboardRunSnapshot pseudo = fixture.service.approve(plot.runId(),
                new Approval(Stage.PLOT_REVIEW, "APPLY_EXPANSION", "", List.of(), List.of()));
        fixture.store.upsertShot(new ShotRecord(
                pseudo.runId(), Phase.FINAL, "segment-1", "shot-1", 1,
                "completed", "{}", List.of("final-reference"), "", "", 1));

        var resolution = fixture.service.referenceAssetIds(pseudo.runId(), "shot-1");

        assertThat(resolution.assetIds()).containsExactly("final-reference");
        assertThat(resolution.expectedAssetIds()).containsExactly("final-reference");
        assertThat(resolution.fallbackUsed()).isFalse();
        assertThat(resolution.finalRecordFound()).isTrue();
    }

    @Test
    void referenceAssetIdsFallBackToPseudoWhenFinalRecordIsMissing() {
        Fixture fixture = fixture();
        StoryboardRunSnapshot plot = fixture.service.start(request("完整剧情：主角发现真相并作出选择。"));
        StoryboardRunSnapshot pseudo = fixture.service.approve(plot.runId(),
                new Approval(Stage.PLOT_REVIEW, "APPLY_EXPANSION", "", List.of(), List.of()));

        var resolution = fixture.service.referenceAssetIds(pseudo.runId(), "shot-1");

        assertThat(resolution.assetIds()).containsExactly("asset-1");
        assertThat(resolution.fallbackUsed()).isTrue();
        assertThat(resolution.finalRecordFound()).isFalse();
        assertThat(resolution.pseudoRecordFound()).isTrue();
    }

    @Test
    void replacesEditableDraftOnlyAtTheCurrentVersionAndStage() {
        Fixture fixture = fixture();
        StoryboardRunSnapshot plot = fixture.service.start(request("完整剧情：主角发现真相并作出选择。"));
        StoryboardRunSnapshot pseudo = fixture.service.approve(plot.runId(),
                new Approval(Stage.PLOT_REVIEW, "APPLY_EXPANSION", "", List.of(), List.of()));
        PseudoShot edited = new PseudoShot("shot-2", "segment-1", 1, "调整目的",
                List.of("主角"), "车站", "清晨", "错过", "追上车", "坚定",
                List.of("连续"), List.of("原文"), List.of());

        StoryboardRunSnapshot updated = fixture.service.replaceDraft(
                pseudo.runId(), "user-1", "project-1", pseudo.version(),
                List.of(edited), List.of());

        assertThat(updated.pseudoShots()).containsExactly(edited);
        assertThat(updated.version()).isEqualTo(pseudo.version() + 1);
        assertThatThrownBy(() -> fixture.service.replaceDraft(
                updated.runId(), "user-1", "project-1", pseudo.version(),
                List.of(edited), List.of()))
                .hasMessageContaining("版本已变化");
    }

    private static Fixture fixture() {
        FakeModelClient model = new FakeModelClient();
        InMemoryStore store = new InMemoryStore();
        AgentAiConfigProperties config = new AgentAiConfigProperties();
        config.setMaxParallelAgents(4);
        StoryboardRoleRegistry registry = new StoryboardRoleRegistry();
        registry.load();
        return new Fixture(model, store,
                new StoryboardProductionServiceImpl(model, store, registry, config));
    }

    private static StoryboardProductionRequest request(String script) {
        return new StoryboardProductionRequest(
                "user-1", "project-1", "conversation-1", "canvas-1",
                script, "movie", "", List.of(), List.of());
    }

    private record Fixture(FakeModelClient model, InMemoryStore store,
                           StoryboardProductionService service) {
    }

    private static final class FakeModelClient implements StoryboardModelClient {
        private final AtomicInteger finalCalls = new AtomicInteger();
        private final AtomicInteger optimizeCalls = new AtomicInteger();
        private final AtomicInteger repairCalls = new AtomicInteger();
        private final AtomicBoolean assessmentComplete = new AtomicBoolean(false);
        private final AtomicReference<String> failShotId = new AtomicReference<>("");
        private String lastSegmentScript = "";
        private String lastAssessedScript = "";
        private String lastRepairScript = "";

        @Override
        public PlotAssessment assessPlot(StoryboardProductionRequest request, List<AssetRef> assets) {
            lastAssessedScript = request.originalScript();
            return new PlotAssessment(assessmentComplete.get(), List.of("结局"), List.of("冲突不足"),
                    "剧情缺少收束", "补足主角选择与代价");
        }

        @Override
        public ScriptRepairResult repairScript(StoryboardProductionRequest request,
                                               PlotAssessment assessment,
                                               List<RoleProfile> roles,
                                               List<AssetRef> assets) {
            repairCalls.incrementAndGet();
            lastRepairScript = request.originalScript();
            return new ScriptRepairResult(
                    "修正后的完整剧情",
                    new StoryBible("一句话", List.of("主角"), List.of(), List.of(),
                            List.of("夜晚"), List.of("同一件外套"), List.of("写实")),
                    List.of(new ScriptChange("冲突不足", "完整剧情", "修正后的完整剧情", "补足冲突")));
        }

        @Override
        public OptimizationResult optimizeScript(StoryboardProductionRequest request,
                                                 PlotAssessment assessment,
                                                 List<RoleProfile> roles,
                                                 List<AssetRef> assets,
                                                 String feedback) {
            optimizeCalls.incrementAndGet();
            return new OptimizationResult("优化后的完整剧情", "扩写后的完整剧情",
                    new StoryBible("一句话", List.of("主角"), List.of(), List.of(),
                            List.of("夜晚"), List.of("同一件外套"), List.of("写实")));
        }

        @Override
        public List<StoryboardSegment> segmentStory(String script, StoryBible storyBible) {
            lastSegmentScript = script;
            return List.of(new StoryboardSegment("segment-1", 1, "第一段", script, ""));
        }

        @Override
        public List<PseudoShot> planPseudoShots(StoryboardProductionRequest request,
                                                StoryboardSegment segment,
                                                StoryBible storyBible,
                                                List<RoleProfile> roles,
                                                List<AssetRef> assets) {
            return List.of(
                    new PseudoShot("shot-2", "segment-1", 2, "选择", List.of("主角"),
                            "医院", "夜", "代价", "作出选择", "紧张",
                            List.of("外套连续"), List.of("原文二"), List.of()),
                    new PseudoShot("shot-1", "segment-1", 1, "发现", List.of("主角"),
                            "医院", "夜", "真相", "发现真相", "震惊",
                            List.of(), List.of("原文一"), List.of("asset-1"))
            );
        }

        @Override
        public List<PseudoShot> revisePseudoShots(List<PseudoShot> shots, String instruction,
                                                  String shotId, StoryBible storyBible,
                                                  List<AssetRef> assets) {
            return shots;
        }

        @Override
        public StoryboardShot generateShot(ShotGenerationRequest request) {
            PseudoShot pseudo = request.pseudoShot();
            finalCalls.incrementAndGet();
            if (pseudo.shotId().equals(failShotId.get())) {
                throw new IllegalStateException("forced shot failure");
            }
            return new StoryboardShot(
                    pseudo.shotId(), pseudo.segmentId(), pseudo.shotOrdinal(),
                    pseudo.action(), "中景", "推近", 3,
                    "图片提示", "视频提示", "模糊", List.of());
        }
    }

    private static final class InMemoryStore implements StoryboardRunStore {
        private final Map<String, RunState> runs = new LinkedHashMap<>();
        private final Map<String, ShotRecord> shots = new LinkedHashMap<>();

        @Override
        public RunState insert(RunState state) {
            runs.put(state.runId(), state);
            return state;
        }

        @Override
        public RunState update(RunState state) {
            runs.put(state.runId(), state);
            return state;
        }

        @Override
        public Optional<RunState> find(String runId) {
            return Optional.ofNullable(runs.get(runId));
        }

        @Override
        public Optional<RunState> findLatest(String conversationId) {
            return runs.values().stream()
                    .filter(state -> conversationId != null
                            && conversationId.equals(state.conversationId()))
                    .max(java.util.Comparator.comparing(RunState::updatedAt));
        }

        @Override
        public void upsertShot(ShotRecord record) {
            shots.put(record.runId() + ":" + record.phase() + ":" + record.shotId(), record);
        }

        @Override
        public List<ShotRecord> listShots(String runId, Phase phase) {
            return shots.values().stream()
                    .filter(record -> record.runId().equals(runId) && record.phase() == phase)
                    .toList();
        }
    }
}
