package com.moon.moonagent.ai.storyboard.production;

import com.moon.moonagent.ai.AgentAiConfigProperties;
import com.moon.moonagent.ai.storyboard.StoryboardShot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.Approval;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.AssetRef;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.OptimizationResult;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.OutlinePlan;
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
        // 这一段验证的是旧「诊断 + 重写」回路，必须显式打开；生产默认是一次成文（见下一个用例）
        ((StoryboardProductionServiceImpl) fixture.service).setSelfCheckEnabled(true);
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

    /**
     * 默认行为（一次成文）：不做剧情诊断、不做自我重写，镜头参数全部由代码后处理收敛。
     * 这是为了消掉「同一份剧本被读一次写两次」造成的 84~253s 波动。
     */
    /**
     * 阶段 A：只产极简大纲（梗概 + N 行），停在 PLOT_REVIEW 等审批。
     * 关键断言：不写整部剧本（optimizeCalls=0）、不诊断（assessCalls=0）、不规划伪分镜。
     */
    @Test
    void outlinePhaseProducesThinPlanOnlyAndWaitsForApproval() {
        Fixture fixture = fixture();

        StoryboardRunSnapshot outline = fixture.service.start(request("雨夜，主角走进旧仓库。", "3"));

        assertThat(outline.stage()).isEqualTo(Stage.PLOT_REVIEW);
        assertThat(outline.pseudoShots()).hasSize(3);
        // 大纲行只带「镜号 + 一句画面 + 角色/地点/资产」，不含景别与运镜（那两个字段在正式镜头里才有）
        assertThat(outline.pseudoShots()).allSatisfy(shot -> {
            assertThat(shot.action()).startsWith("大纲画面");
            assertThat(shot.location()).isEqualTo("旧仓库");
            assertThat(shot.characters()).containsExactly("主角");
        });
        assertThat(outline.selectedScript()).isEqualTo("雨夜的极简梗概");
        assertThat(outline.finalShots()).isEmpty();
        assertThat(fixture.model.outlineCalls).hasValue(1);
        assertThat(fixture.model.optimizeCalls).hasValue(0);
        assertThat(fixture.model.assessCalls).hasValue(0);
        assertThat(fixture.model.pseudoPlanCalls).hasValue(0);
    }

    /**
     * 阶段 B：审批大纲后直接逐镜补全，跳过 segmentStory + planPseudoShots（省掉一次模型调用）。
     */
    @Test
    void outlineApprovalGoesStraightToFinalShots() {
        Fixture fixture = fixture();
        StoryboardRunSnapshot outline = fixture.service.start(request("雨夜，主角走进旧仓库。", "2"));

        StoryboardRunSnapshot finalReview = fixture.service.approveToFinal(
                outline.runId(), new Approval(Stage.PLOT_REVIEW, "APPROVE", "", List.of(), List.of()));

        assertThat(finalReview.stage()).isEqualTo(Stage.FINAL_REVIEW);
        assertThat(finalReview.finalShots()).hasSize(2);
        assertThat(finalReview.finalShots()).allSatisfy(shot -> {
            // 补全阶段产出真实景别/运镜，且时长被收敛到合法档位
            assertThat(shot.shotType()).isEqualTo("中景");
            assertThat(shot.cameraMovement()).isEqualTo("推近");
            assertThat(shot.durationSeconds()).isEqualTo(4);
        });
        assertThat(fixture.model.segmentCalls).hasValue(0);
        assertThat(fixture.model.pseudoPlanCalls).hasValue(0);
        assertThat(fixture.model.finalCalls).hasValue(2);
    }

    /**
     * 大纲审批阶段（PLOT_REVIEW）必须可直接改草稿：改一句话就省掉「驳回 → 重跑大纲」。
     */
    @Test
    void outlineDraftIsEditableBeforeApproval() {
        Fixture fixture = fixture();
        StoryboardRunSnapshot outline = fixture.service.start(request("雨夜，主角走进旧仓库。", "2"));
        assertThat(outline.stage()).isEqualTo(Stage.PLOT_REVIEW);

        PseudoShot first = new PseudoShot("shot-1", "outline", 1, "", List.of("主角"),
                "旧仓库", "夜", "", "改过的第一镜画面", "", List.of(), List.of(), List.of());
        PseudoShot second = outline.pseudoShots().get(1);

        StoryboardRunSnapshot updated = fixture.service.replaceDraft(
                outline.runId(), "user-1", "project-1", outline.version(),
                List.of(first, second), List.of());

        assertThat(updated.stage()).isEqualTo(Stage.PLOT_REVIEW);
        assertThat(updated.version()).isEqualTo(outline.version() + 1);
        assertThat(updated.pseudoShots()).extracting(PseudoShot::action)
                .containsExactly("改过的第一镜画面", second.action());

        // 改完仍然能正常审批进入正式镜头
        StoryboardRunSnapshot finalReview = fixture.service.approveToFinal(updated.runId(),
                new Approval(Stage.PLOT_REVIEW, "APPROVE", "", List.of(), List.of()));
        assertThat(finalReview.stage()).isEqualTo(Stage.FINAL_REVIEW);
        assertThat(finalReview.finalShots()).hasSize(2);
    }

    @Test
    void outlineDraftRejectsStaleVersion() {
        Fixture fixture = fixture();
        StoryboardRunSnapshot outline = fixture.service.start(request("雨夜，主角走进旧仓库。", "2"));

        assertThatThrownBy(() -> fixture.service.replaceDraft(
                outline.runId(), "user-1", "project-1", outline.version() + 5,
                List.of(), List.of()))
                .hasMessageContaining("版本已变化");
    }

    /**
     * 正式镜头必须分批扩写：7 镜 + 批大小 3 → 3 批（3/3/1），而不是 7 次独立调用。
     */
    @Test
    void finalShotsAreRequestedInBatches() {
        Fixture fixture = fixture();
        ((StoryboardProductionServiceImpl) fixture.service).setFinalBatchSize(3);
        StoryboardRunSnapshot outline = fixture.service.start(request("雨夜，主角走进旧仓库。", "7"));

        StoryboardRunSnapshot finalReview = fixture.service.approveToFinal(outline.runId(),
                new Approval(Stage.PLOT_REVIEW, "APPROVE", "", List.of(), List.of()));

        assertThat(finalReview.stage()).isEqualTo(Stage.FINAL_REVIEW);
        assertThat(finalReview.finalShots()).hasSize(7);
        assertThat(fixture.model.batchSizes).containsExactlyInAnyOrder(3, 3, 1);
    }

    /** 批大小设为 1 时必须完全回退成逐镜调用（线上回滚开关）。 */
    @Test
    void batchSizeOneFallsBackToPerShotGeneration() {
        Fixture fixture = fixture();
        ((StoryboardProductionServiceImpl) fixture.service).setFinalBatchSize(1);
        StoryboardRunSnapshot outline = fixture.service.start(request("雨夜，主角走进旧仓库。", "3"));

        fixture.service.approveToFinal(outline.runId(),
                new Approval(Stage.PLOT_REVIEW, "APPROVE", "", List.of(), List.of()));

        assertThat(fixture.model.batchSizes).containsOnly(1).hasSize(3);
    }

    /**
     * 批量漏镜自愈：模型少写一镜时先单独补一次，不能让整轮掉到 partial。
     * （实测 12 镜跑出过 11/12，missing 那一镜直接被记失败。）
     */
    /**
     * 改动日志：用户在审批前改一句画面，要落一条字段级 UPDATE（before/after 都能追溯）。
     */
    @Test
    void replaceDraftAppendsFieldLevelChanges() {
        Fixture fixture = fixture();
        StoryboardRunSnapshot outline = fixture.service.start(request("雨夜，主角走进旧仓库。", "4"));
        String shotId = outline.pseudoShots().get(1).shotId();
        String original = outline.pseudoShots().get(1).action();

        List<com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.PseudoShot> edited =
                new ArrayList<>(outline.pseudoShots());
        edited.set(1, new com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.PseudoShot(
                shotId, edited.get(1).segmentId(), edited.get(1).shotOrdinal(),
                edited.get(1).narrativePurpose(), edited.get(1).characters(),
                edited.get(1).location(), edited.get(1).time(), edited.get(1).conflict(),
                "改过之后的画面", edited.get(1).emotion(), edited.get(1).continuity(),
                edited.get(1).sourceExcerpt(), edited.get(1).referenceAssetIds()));

        fixture.service.replaceDraft(outline.runId(), "user-1", "project-1",
                outline.version(), edited, List.of());

        var changes = fixture.service.changes(outline.runId(), "user-1", "project-1");
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).field()).isEqualTo("画面");
        assertThat(changes.get(0).source()).isEqualTo("user");
        assertThat(changes.get(0).before()).isEqualTo(original);
        assertThat(changes.get(0).after()).isEqualTo("改过之后的画面");
    }

    @Test
    void missingShotFromBatchIsRetriedIndividually() {
        Fixture fixture = fixture();
        ((StoryboardProductionServiceImpl) fixture.service).setFinalBatchSize(6);
        fixture.model.dropFromBatch.set("shot-4");
        StoryboardRunSnapshot outline = fixture.service.start(request("雨夜，主角走进旧仓库。", "8"));

        StoryboardRunSnapshot finalReview = fixture.service.approveToFinal(outline.runId(),
                new Approval(Stage.PLOT_REVIEW, "APPROVE", "", List.of(), List.of()));

        assertThat(fixture.model.droppedFromBatch).hasValue(1);
        assertThat(finalReview.stage()).isEqualTo(Stage.FINAL_REVIEW);
        assertThat(finalReview.finalShots()).hasSize(8);
        assertThat(finalReview.finalShots()).extracting(StoryboardShot::shotId).contains("shot-4");
    }

    @Test
    void autoStoryboardRunsWithoutSelfDiagnosisByDefault() {
        Fixture fixture = fixture();

        StoryboardRunSnapshot snapshot = fixture.service.startAutoStoryboard(
                request("完整剧情：主角发现真相并作出选择。"), (stage, value) -> {
                });

        assertThat(snapshot.stage()).isEqualTo(Stage.FINAL_REVIEW);
        assertThat(fixture.model.assessCalls).hasValue(0);
        assertThat(fixture.model.repairCalls).hasValue(0);
        assertThat(fixture.model.optimizeCalls).hasValue(1);
        assertThat(snapshot.selectedScript()).isEqualTo("扩写后的完整剧情");
        assertThat(snapshot.assessment().summary()).contains("跳过剧情诊断");
        // 假模型固定返回 3s / 中景 / 推近：3s 不是合法档位，必须被代码收敛到 4s
        assertThat(snapshot.finalShots()).isNotEmpty();
        assertThat(snapshot.finalShots()).allSatisfy(shot -> {
            assertThat(shot.durationSeconds()).isEqualTo(4);
            assertThat(shot.shotType()).isEqualTo("中景");
            assertThat(shot.cameraMovement()).isEqualTo("推近");
        });
    }

    @Test
    void autoStoryboardSkipsRepairWhenPlotIsComplete() {
        Fixture fixture = fixture();
        ((StoryboardProductionServiceImpl) fixture.service).setSelfCheckEnabled(true);
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
    void explicitRequestedShotCountUsesSingleGlobalPlanAndCapsOutput() {
        Fixture fixture = fixture();

        StoryboardRunSnapshot snapshot = fixture.service.startAutoStoryboard(
                request("完整展示脚本", "6"), (stage, value) -> {
                });

        assertThat(snapshot.pseudoShots()).hasSize(6);
        assertThat(snapshot.finalShots()).hasSize(6);
        assertThat(fixture.model.segmentCalls).hasValue(0);
        assertThat(fixture.model.pseudoPlanCalls).hasValue(1);
        assertThat(fixture.model.lastRequestedShotCount).isEqualTo("6");
    }

    @Test
    void requiresPlotReviewBeforePseudoReview() {
        Fixture fixture = fixture();
        // 旧三段流程（写整部剧本 → 伪分镜 → 正式镜头）；默认已切到极简大纲，这里显式回退
        ((StoryboardProductionServiceImpl) fixture.service).setOutlineFirstEnabled(false);
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
        ((StoryboardProductionServiceImpl) fixture.service).setOutlineFirstEnabled(false);
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
    void approvingAnAlreadyPassedStageIsIdempotent() {
        Fixture fixture = fixture();
        StoryboardRunSnapshot plot = fixture.service.start(request("完整剧情：主角发现真相并作出选择。"));
        StoryboardRunSnapshot pseudo = fixture.service.approve(plot.runId(),
                new Approval(Stage.PLOT_REVIEW, "APPLY_EXPANSION", "", List.of(), List.of()));

        // 旧卡片重复点击「确认剧情」：目标阶段已推进过，按幂等返回当前快照而不是失败
        StoryboardRunSnapshot again = fixture.service.approve(pseudo.runId(),
                new Approval(Stage.PLOT_REVIEW, "APPROVE", "", List.of(), List.of()));

        assertThat(again.stage()).isEqualTo(Stage.PSEUDO_REVIEW);
        assertThat(again.version()).isEqualTo(pseudo.version());
        assertThat(fixture.model.finalCalls).hasValue(0);
    }

    @Test
    void retryingIncompleteFinalShotsRegeneratesOnlyMissingShots() {
        Fixture fixture = fixture();
        fixture.model.failShotId.set("shot-2");
        StoryboardRunSnapshot plot = fixture.service.start(request("完整剧情：主角发现真相并作出选择。"));
        StoryboardRunSnapshot pseudo = fixture.service.approve(plot.runId(),
                new Approval(Stage.PLOT_REVIEW, "APPLY_EXPANSION", "", List.of(), List.of()));
        StoryboardRunSnapshot partial = fixture.service.approve(pseudo.runId(),
                new Approval(Stage.PSEUDO_REVIEW, "APPROVE", "", List.of(), List.of()));
        assertThat(partial.stage()).isEqualTo(Stage.FINALIZING);

        fixture.model.failShotId.set("");
        int callsBeforeRetry = fixture.model.finalCalls.get();
        StoryboardRunSnapshot resumed = fixture.service.retryIncompleteFinalShots(partial.runId());

        assertThat(resumed.stage()).isEqualTo(Stage.FINAL_REVIEW);
        assertThat(resumed.finalShots()).extracting(StoryboardShot::shotId)
                .containsExactlyInAnyOrder("shot-1", "shot-2");
        // 关键语义：只补跑缺失的那一镜（与逐镜/分批无关，所以比「总调用次数」稳）
        assertThat(fixture.model.finalCalls.get() - callsBeforeRetry).isEqualTo(1);
    }

    @Test
    void crossSegmentShotIdsStayUniqueSoProjectMappingCanComplete() {
        Fixture fixture = fixture();
        fixture.model.crossSegmentIdCollision.set(true);
        StoryboardRunSnapshot plot = fixture.service.start(request("完整剧情：主角发现真相并作出选择。"));
        StoryboardRunSnapshot pseudo = fixture.service.approve(plot.runId(),
                new Approval(Stage.PLOT_REVIEW, "APPLY_EXPANSION", "", List.of(), List.of()));

        assertThat(pseudo.pseudoShots()).hasSize(4);
        assertThat(pseudo.pseudoShots()).extracting(PseudoShot::shotId).doesNotHaveDuplicates();

        StoryboardRunSnapshot finalReview = fixture.service.approve(pseudo.runId(),
                new Approval(Stage.PSEUDO_REVIEW, "APPROVE", "", List.of(), List.of()));
        assertThat(finalReview.finalShots()).hasSize(4);
        assertThat(finalReview.finalShots()).extracting(StoryboardShot::shotId).doesNotHaveDuplicates();

        Map<String, String> mapping = new LinkedHashMap<>();
        int index = 0;
        for (StoryboardShot shot : finalReview.finalShots()) {
            mapping.put(shot.shotId(), "scene-" + (++index));
        }
        StoryboardRunSnapshot completed = fixture.service.markCompleted(
                finalReview.runId(), "user-1", "project-1", mapping);

        assertThat(completed.stage()).isEqualTo(Stage.COMPLETED);
        assertThat(fixture.service.publishedSceneIds(completed.runId()))
                .containsExactlyInAnyOrderElementsOf(mapping.values());
    }

    @Test
    void committingLegacyRunWithDuplicateShotIdsNoLongerFailsOnMapping() {
        Fixture fixture = fixture();
        StoryboardShot first = new StoryboardShot(
                "S01", "segment-1", 1, "画面一", "中景", "推近", 3, "图", "视", "", List.of());
        StoryboardShot duplicate = new StoryboardShot(
                "S01", "segment-3", 2, "画面二", "近景", "横移", 3, "图", "视", "", List.of());
        fixture.store.insert(new RunState(
                "run-legacy", "user-1", "project-1", "conversation-1", "canvas-1",
                "原始剧情", "剧情稿", "", Stage.FINAL_REVIEW, 3,
                new PlotAssessment(false, List.of(), List.of(), "", ""), StoryBible.empty(),
                List.of(), List.of(), List.of(), List.of(first, duplicate),
                "", OffsetDateTime.now(), OffsetDateTime.now()));

        StoryboardRunSnapshot completed = fixture.service.markCompleted(
                "run-legacy", "user-1", "project-1", Map.of("S01", "scene-1"));

        assertThat(completed.stage()).isEqualTo(Stage.COMPLETED);
        assertThat(completed.finalShots()).hasSize(1);
        assertThat(fixture.service.publishedSceneIds("run-legacy")).containsExactly("scene-1");
    }

    @Test
    void publishedSceneIdsListMappedProjectScenesOnce() {
        Fixture fixture = fixture();
        StoryboardRunSnapshot plot = fixture.service.start(request("完整剧情：主角发现真相并作出选择。"));
        StoryboardRunSnapshot pseudo = fixture.service.approve(plot.runId(),
                new Approval(Stage.PLOT_REVIEW, "APPLY_EXPANSION", "", List.of(), List.of()));
        StoryboardRunSnapshot finalReview = fixture.service.approve(pseudo.runId(),
                new Approval(Stage.PSEUDO_REVIEW, "APPROVE", "", List.of(), List.of()));

        assertThat(fixture.service.publishedSceneIds(finalReview.runId())).isEmpty();

        fixture.service.markCompleted(finalReview.runId(), "user-1", "project-1",
                Map.of("shot-1", "scene-1", "shot-2", "scene-2"));

        assertThat(fixture.service.publishedSceneIds(finalReview.runId()))
                .containsExactlyInAnyOrder("scene-1", "scene-2");
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
        return request(script, "");
    }

    private static StoryboardProductionRequest request(String script, String requestedShotCount) {
        return new StoryboardProductionRequest(
                "user-1", "project-1", "conversation-1", "canvas-1",
                script, "movie", requestedShotCount, List.of(), List.of());
    }

    private record Fixture(FakeModelClient model, InMemoryStore store,
                           StoryboardProductionService service) {
    }

    private static final class FakeModelClient implements StoryboardModelClient {
        private final AtomicInteger finalCalls = new AtomicInteger();
        private final AtomicInteger optimizeCalls = new AtomicInteger();
        private final AtomicInteger assessCalls = new AtomicInteger();
        private final AtomicInteger outlineCalls = new AtomicInteger();
        private final AtomicInteger repairCalls = new AtomicInteger();
        private final AtomicInteger segmentCalls = new AtomicInteger();
        private final AtomicInteger pseudoPlanCalls = new AtomicInteger();
        private final AtomicBoolean assessmentComplete = new AtomicBoolean(false);
        private final AtomicReference<String> failShotId = new AtomicReference<>("");
        /** 模拟批量漏镜：放这个 shotId 时批量返回里故意不带它。 */
        private final AtomicReference<String> dropFromBatch = new AtomicReference<>("");
        private final AtomicInteger droppedFromBatch = new AtomicInteger();
        private final AtomicBoolean crossSegmentIdCollision = new AtomicBoolean(false);
        /** 每次批量扩写实际带的镜数（顺序取决于并发执行顺序，断言用 anyOrder）。 */
        private final List<Integer> batchSizes =
                java.util.Collections.synchronizedList(new ArrayList<>());
        private String lastSegmentScript = "";
        private String lastAssessedScript = "";
        private String lastRepairScript = "";
        private String lastRequestedShotCount = "";

        @Override
        public PlotAssessment assessPlot(StoryboardProductionRequest request, List<AssetRef> assets) {
            assessCalls.incrementAndGet();
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
        public OutlinePlan planOutline(StoryboardProductionRequest request, List<RoleProfile> roles,
                                       List<AssetRef> assets, StoryboardStreamSink sink) {
            outlineCalls.incrementAndGet();
            int total = 2;
            try {
                String requested = request.requestedShotCount();
                if (requested != null && !requested.isBlank()) total = Integer.parseInt(requested.trim());
            } catch (NumberFormatException ignored) {
                total = 2;
            }
            List<PseudoShot> shots = new ArrayList<>();
            for (int ordinal = 1; ordinal <= total; ordinal++) {
                shots.add(new PseudoShot(
                        "shot-" + ordinal, "outline", ordinal, "", List.of("主角"),
                        "旧仓库", "夜", "", "大纲画面" + ordinal, "",
                        List.of(), List.of(), List.of()));
            }
            return new OutlinePlan("雨夜的极简梗概", StoryBible.empty(), shots);
        }

        @Override
        public List<StoryboardSegment> segmentStory(String script, StoryBible storyBible) {
            segmentCalls.incrementAndGet();
            lastSegmentScript = script;
            if (crossSegmentIdCollision.get()) {
                return List.of(
                        new StoryboardSegment("segment-1", 1, "第一段", script, ""),
                        new StoryboardSegment("segment-3", 2, "第三段", script, ""));
            }
            return List.of(new StoryboardSegment("segment-1", 1, "第一段", script, ""));
        }

        @Override
        public List<PseudoShot> planPseudoShots(StoryboardProductionRequest request,
                                                StoryboardSegment segment,
                                                StoryBible storyBible,
                                                List<RoleProfile> roles,
                                                List<AssetRef> assets) {
            pseudoPlanCalls.incrementAndGet();
            lastRequestedShotCount = request.requestedShotCount();
            if (!request.requestedShotCount().isBlank()) {
                List<PseudoShot> requested = new ArrayList<>();
                for (int index = 1; index <= 12; index++) {
                    requested.add(new PseudoShot(
                            "shot-" + index, "segment-1", index,
                            "目的" + index, List.of("主角"), "场景", "时间", "冲突",
                            "动作" + index, "情绪", List.of(), List.of("原文"), List.of()));
                }
                return requested;
            }
            // 复现线上问题：每个分段各自从 S01 编号，跨分段 shotId 冲突
            if (crossSegmentIdCollision.get()) {
                return List.of(
                        new PseudoShot("S01", segment.segmentId(), 1, "发现", List.of("主角"),
                                "医院", "夜", "真相", "发现真相", "震惊",
                                List.of(), List.of("原文一"), List.of()),
                        new PseudoShot("S02", segment.segmentId(), 2, "选择", List.of("主角"),
                                "医院", "夜", "代价", "作出选择", "紧张",
                                List.of(), List.of("原文二"), List.of()));
            }
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
        public List<StoryboardShot> generateShots(List<ShotGenerationRequest> requests,
                                                  StoryboardStreamSink sink) {
            batchSizes.add(requests.size());
            List<StoryboardShot> out = new ArrayList<>(requests.size());
            for (ShotGenerationRequest request : requests) {
                // 模拟「批量漏镜」：该镜不出现在批量返回里，但单独调用仍能成功
                if (request.pseudoShot().shotId().equals(dropFromBatch.get())) {
                    droppedFromBatch.incrementAndGet();
                    continue;
                }
                out.add(generateShot(request, sink));
            }
            return List.copyOf(out);
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
        private final java.util.List<StoryboardChangeLog.ShotChange> changes = new java.util.ArrayList<>();

        @Override
        public void appendChanges(java.util.List<StoryboardChangeLog.ShotChange> appended) {
            if (appended != null) changes.addAll(appended);
        }

        @Override
        public java.util.List<StoryboardChangeLog.ShotChange> listChanges(String runId) {
            return changes.stream().filter(change -> runId.equals(change.runId())).toList();
        }

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
