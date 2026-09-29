package com.moon.moonagent.ai.storyboard.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.AgentAiConfigProperties;
import com.moon.moonagent.ai.storyboard.StoryboardShot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.Approval;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.OptimizationResult;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.Phase;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.PlotAssessment;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.PseudoShot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.RoleProfile;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.RunState;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.ScriptRepairResult;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.ShotGenerationRequest;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.ShotRecord;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.ShotReferenceResolution;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.Stage;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryBible;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardProductionRequest;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardRunSnapshot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryboardSegment;
import com.storyboard.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class StoryboardProductionServiceImpl implements StoryboardProductionService {

    private static final Logger log = LoggerFactory.getLogger(StoryboardProductionServiceImpl.class);
    private static final Pattern SHOT_COUNT_VALUE = Pattern.compile(
            "([0-9]{1,3}|[一二三四五六七八九十百]{1,4})");
    private static final Pattern EXPLICIT_SHOT_COUNT = Pattern.compile(
            "([0-9]{1,3}|[一二三四五六七八九十百]{1,4})\\s*个?\\s*(?:分镜|镜头)");
    private final StoryboardModelClient modelClient;
    private final StoryboardRunStore runStore;
    private final StoryboardRoleRegistry roleRegistry;
    private final AgentAiConfigProperties agentConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Value("${ai.storyboard.production-v2.long-script-threshold:6000}")
    private int longScriptThreshold = 6000;

    @Value("${ai.storyboard.production-v2.max-shots:200}")
    private int maxShots = 200;

    @Value("${ai.storyboard.production-v2.max-segments:12}")
    private int maxSegments = 12;

    @Override
    public StoryboardRunSnapshot start(StoryboardProductionRequest request) {
        return start(request, StoryboardProgressListener.NOOP);
    }

    @Override
    public StoryboardRunSnapshot start(StoryboardProductionRequest request,
                                       StoryboardProgressListener listener) {
        StoryboardProgressListener progress =
                listener == null ? StoryboardProgressListener.NOOP : listener;
        if (request == null || request.originalScript().isBlank()) {
            throw new BusinessException(40001, "剧情不能为空");
        }
        boolean multiSegmentCandidate = request.originalScript().length() > longScriptThreshold;
        var roles = roleRegistry.resolve(request.originalScript(), request.roleHints(), multiSegmentCandidate);
        progress.update("script_optimization", 8);
        var assessment = modelClient.assessPlot(request, request.assets(), sinkTo(progress));
        appendDiagnosisThinking(progress, assessment);
        var optimization = modelClient.optimizeScript(request, assessment, roles, request.assets(), "",
                sinkTo(progress));
        OffsetDateTime now = OffsetDateTime.now();
        RunState state = new RunState(
                UUID.randomUUID().toString(), request.userId(), request.projectId(),
                request.conversationId(), request.canvasId(), request.originalScript(),
                request.requestedShotCount(), optimization.optimizedScript(),
                optimization.expansionScript(), Stage.PLOT_REVIEW, 1,
                assessment, optimization.storyBible(), roles, request.assets(),
                List.of(), List.of(), "", now, now);
        return runStore.insert(state).snapshot();
    }

    @Override
    public StoryboardRunSnapshot startAutoStoryboard(
            StoryboardProductionRequest request,
            StoryboardProgressListener progressListener) {
        if (request == null || request.originalScript().isBlank()) {
            throw new BusinessException(40001, "剧情不能为空");
        }
        StoryboardProgressListener progress =
                progressListener == null ? StoryboardProgressListener.NOOP : progressListener;
        Integer requestedShotCount = requestedShotCount(request);
        boolean multiSegmentCandidate = requestedShotCount == null
                && request.originalScript().length() > longScriptThreshold;
        var roles = roleRegistry.resolve(request.originalScript(), request.roleHints(), multiSegmentCandidate);

        progress.update("script_optimization", 8);
        PlotAssessment generationAssessment = new PlotAssessment(
                false,
                List.of("尚未形成可直接进入分镜的完整剧情或展示脚本"),
                List.of(),
                "用户提供的是创作需求，需要先补全为完整剧情或展示脚本",
                "保留用户核心设定，生成完整、连续、可直接进入结构化分镜的剧情或展示脚本");
        OptimizationResult generation = modelClient.optimizeScript(
                request, generationAssessment, roles, request.assets(), "", sinkTo(progress));
        String generatedScript = firstNonBlank(
                generation.expansionScript(), generation.optimizedScript(), request.originalScript());
        progress.plotReady(generatedScript);
        StoryboardProductionRequest generatedRequest = withOriginalScript(request, generatedScript);

        progress.update("plot_diagnosis", 25);
        PlotAssessment assessment = modelClient.assessPlot(
                generatedRequest, request.assets(), sinkTo(progress));

        ScriptRepairResult repair;
        if (assessment.complete()) {
            repair = new ScriptRepairResult(
                    generatedScript, generation.storyBible(), List.of());
        } else {
            progress.update("script_repair", 40);
            repair = repairScriptWithOneRetry(generatedRequest, assessment, roles, sinkTo(progress));
        }
        String selectedScript = repair.revisedScript().isBlank()
                ? generatedScript : repair.revisedScript();
        StoryBible storyBible = hasStoryBible(repair.storyBible())
                ? repair.storyBible() : generation.storyBible();

        OffsetDateTime now = OffsetDateTime.now();
        RunState state = new RunState(
                UUID.randomUUID().toString(), request.userId(), request.projectId(),
                request.conversationId(), request.canvasId(), request.originalScript(),
                request.requestedShotCount(), selectedScript, "", Stage.FINALIZING, 1,
                assessment, storyBible, roles, request.assets(),
                List.of(), List.of(), "", now, now);
        state = runStore.insert(state);

        progress.update("story_segmentation", 55);
        List<StoryboardSegment> segments = requestedShotCount == null
                ? normalizeSegments(modelClient.segmentStory(selectedScript, storyBible, sinkTo(progress)))
                : List.of(new StoryboardSegment("segment-1", 1, "完整剧情", selectedScript, ""));
        if (requestedShotCount != null) {
            progress.stream("story_segmentation",
                    "用户指定总镜头数 " + requestedShotCount + "，按完整剧情统一规划，不拆分镜头配额。\n");
        }
        progress.update("pseudo_planning", 65);
        boolean parallel = requestedShotCount == null && shouldSegment(selectedScript, segments);
        StoryboardProductionRequest planningRequest = requestedShotCount == null
                ? generatedRequest : withRequestedShotCount(generatedRequest, requestedShotCount);
        List<PseudoShot> shots = parallel
                ? generatePseudoShotsParallel(state, segments, roles, progress)
                : modelClient.planPseudoShots(
                planningRequest, segments.getFirst(), storyBible, roles, state.assets(),
                sinkTo(progress));
        shots = normalizeShots(shots, requestedShotCount);
        if (shots.isEmpty()) {
            throw new BusinessException(50201, "未生成结构化分镜");
        }
        if (shots.size() > maxShots) {
            shots = List.copyOf(shots.subList(0, maxShots));
        }
        for (PseudoShot shot : shots) {
            upsertPseudoShotRecord(state.runId(), shot);
        }
        state = runStore.update(copy(state, Stage.FINALIZING, selectedScript, "",
                assessment, storyBible, roles, shots, List.of(), ""));

        progress.update("final_generation", 78);
        List<StoryboardShot> generated = generateFinalShots(state, shots, progress);
        boolean complete = generated.size() == shots.size();
        RunState finished = copy(state, complete ? Stage.FINAL_REVIEW : Stage.FINALIZING,
                selectedScript, "", assessment, storyBible, roles,
                shots, generated, complete ? "" : partialFailureMessage(shots, generated));
        progress.update(complete ? "ready_to_publish" : "partial_failure", complete ? 90 : 78);
        return runStore.update(finished).snapshot();
    }

    private static StoryboardProductionRequest withOriginalScript(
            StoryboardProductionRequest request, String script) {
        return new StoryboardProductionRequest(
                request.userId(), request.projectId(), request.conversationId(), request.canvasId(),
                script, request.creationType(), request.requestedShotCount(),
                request.assets(), request.roleHints());
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static boolean hasStoryBible(StoryboardProductionModels.StoryBible storyBible) {
        return storyBible != null && (!storyBible.logline().isBlank()
                || !storyBible.characters().isEmpty()
                || !storyBible.relationships().isEmpty()
                || !storyBible.worldRules().isEmpty()
                || !storyBible.timeline().isEmpty()
                || !storyBible.continuity().isEmpty()
                || !storyBible.visualStyle().isEmpty());
    }

    private ScriptRepairResult repairScriptWithOneRetry(
            StoryboardProductionRequest request,
            StoryboardProductionModels.PlotAssessment assessment,
            List<RoleProfile> roles,
            StoryboardStreamSink sink) {
        RuntimeException firstFailure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                ScriptRepairResult result = modelClient.repairScript(
                        request, assessment, roles, request.assets(), sink);
                if (result == null || result.revisedScript().isBlank()) {
                    throw new IllegalStateException("剧情修正未返回有效脚本");
                }
                return result;
            } catch (RuntimeException error) {
                if (firstFailure == null) firstFailure = error;
            }
        }
        throw new BusinessException(50201,
                "剧情修正失败: " + rootMessage(firstFailure), firstFailure);
    }

    @Override
    public StoryboardRunSnapshot get(String runId) {
        return require(runId).snapshot();
    }

    @Override
    public StoryboardRunSnapshot getOwned(String runId, String userId, String projectId) {
        RunState state = require(runId);
        if (userId == null || !userId.equals(state.userId())) {
            throw new BusinessException(40401, "分镜运行不存在或无权访问");
        }
        if (projectId != null && !projectId.isBlank()
                && state.projectId() != null && !projectId.equals(state.projectId())) {
            throw new BusinessException(40401, "分镜运行不存在或无权访问");
        }
        return state.snapshot();
    }

    @Override
    public StoryboardRunSnapshot getLatestOwned(
            String userId, String projectId, String conversationId) {
        RunState state = runStore.findLatest(conversationId)
                .orElseThrow(() -> new BusinessException(40401, "分镜运行不存在或无权访问"));
        if (userId == null || !userId.equals(state.userId())) {
            throw new BusinessException(40401, "分镜运行不存在或无权访问");
        }
        if (projectId != null && !projectId.isBlank()
                && state.projectId() != null && !projectId.equals(state.projectId())) {
            throw new BusinessException(40401, "分镜运行不存在或无权访问");
        }
        return state.snapshot();
    }

    @Override
    public StoryboardRunSnapshot revise(String runId, Stage targetStage, String instruction, String shotId) {
        return revise(runId, targetStage, instruction, shotId, StoryboardProgressListener.NOOP);
    }

    @Override
    public StoryboardRunSnapshot revise(String runId, Stage targetStage, String instruction, String shotId,
                                        StoryboardProgressListener listener) {
        StoryboardProgressListener progress =
                listener == null ? StoryboardProgressListener.NOOP : listener;
        RunState state = require(runId);
        if (targetStage == Stage.PLOT_REVIEW) {
            var request = requestOf(state);
            progress.update("script_optimization", 8);
            progress.stream("script_optimization", "按补充要求重新优化剧情…\n\n");
            var optimization = modelClient.optimizeScript(
                    request, state.assessment(), state.roles(), state.assets(), instruction,
                    sinkTo(progress));
            progress.stream("script_optimization", "\n\n剧情稿已更新。\n");
            RunState revised = copy(state, Stage.PLOT_REVIEW, optimization.optimizedScript(),
                    optimization.expansionScript(), state.assessment(), optimization.storyBible(),
                    state.roles(), List.of(), List.of(), "");
            return runStore.update(revised).snapshot();
        }
        if (targetStage == Stage.PSEUDO_REVIEW) {
            progress.update("pseudo_planning", 20);
            progress.stream("pseudo_planning", "按补充要求修订伪分镜…\n");
            List<PseudoShot> shots = modelClient.revisePseudoShots(
                    state.pseudoShots(), instruction == null ? "" : instruction, shotId,
                    state.storyBible(), state.assets(), sinkTo(progress));
            shots = normalizeShots(shots);
            progress.stream("pseudo_planning", "修订后共 " + shots.size() + " 个伪分镜。\n");
            for (PseudoShot revisedShot : shots) upsertPseudoShotRecord(state.runId(), revisedShot);
            RunState revised = copy(state, Stage.PSEUDO_REVIEW, state.selectedScript(),
                    state.expansionScript(), state.assessment(), state.storyBible(),
                    state.roles(), shots, List.of(), "");
            return runStore.update(revised).snapshot();
        }
        throw new BusinessException(40001, "该阶段不支持修改: " + targetStage);
    }

    @Override
    public StoryboardRunSnapshot approve(String runId, Approval approval) {
        return approve(runId, approval, StoryboardProgressListener.NOOP);
    }

    @Override
    public StoryboardRunSnapshot approve(String runId, Approval approval,
                                         StoryboardProgressListener listener) {
        StoryboardProgressListener progress =
                listener == null ? StoryboardProgressListener.NOOP : listener;
        RunState state = require(runId);
        if (approval == null || approval.stage() == null) {
            throw new BusinessException(40001, "确认阶段不能为空");
        }
        if (state.stage() != approval.stage()) {
            // 旧确认卡片重复提交 / 断点续跑：目标阶段已经推进过，按幂等返回当前快照，不再报错。
            if (state.stage().ordinal() > approval.stage().ordinal()) {
                progress.stream(approval.stage().name(), "该阶段已确认，跳过重复操作。\n");
                return state.snapshot();
            }
            throw new BusinessException(40901, "分镜运行阶段已变化，请刷新后重试");
        }
        return approval.stage() == Stage.PLOT_REVIEW
                ? approvePlot(state, approval, progress)
                : approval.stage() == Stage.PSEUDO_REVIEW
                ? approvePseudo(state, approval, progress)
                : fail("当前阶段不可确认: " + approval.stage());
    }

    @Override
    public StoryboardRunSnapshot approveToFinal(String runId, Approval plotApproval) {
        return approveToFinal(runId, plotApproval, StoryboardProgressListener.NOOP);
    }

    @Override
    public StoryboardRunSnapshot approveToFinal(String runId, Approval plotApproval,
                                                StoryboardProgressListener listener) {
        StoryboardProgressListener progress =
                listener == null ? StoryboardProgressListener.NOOP : listener;
        StoryboardRunSnapshot pseudo = approve(runId, plotApproval, progress);
        return approve(pseudo.runId(),
                new Approval(Stage.PSEUDO_REVIEW, "APPROVE", "", List.of(), List.of()), progress);
    }

    @Override
    public StoryboardRunSnapshot upsertShot(String runId, PseudoShot shot) {
        RunState state = require(runId);
        if (state.stage() != Stage.PSEUDO_REVIEW) {
            throw new BusinessException(40901, "只有伪分镜确认阶段可以逐镜写入");
        }
        if (shot == null || shot.shotId().isBlank()) {
            throw new BusinessException(40001, "单镜写入必须提供 shotId");
        }
        List<PseudoShot> nextShots = new ArrayList<>(state.pseudoShots());
        nextShots.removeIf(item -> item.shotId().equals(shot.shotId()));
        nextShots.add(shot);
        nextShots = normalizeShots(nextShots);
        upsertPseudoShotRecord(runId, shot);
        RunState next = copy(state, Stage.PSEUDO_REVIEW, state.selectedScript(), state.expansionScript(), state.assessment(),
                state.storyBible(), state.roles(), nextShots, List.of(), "");
        return runStore.update(next).snapshot();
    }

    private StoryboardRunSnapshot approvePlot(RunState state, Approval approval,
                                              StoryboardProgressListener progress) {
        String selected = selectScript(state, approval.decision());
        var roles = approval.roles().isEmpty() ? state.roles() : approval.roles();
        StoryboardProductionRequest request = requestOf(state);
        Integer requestedShotCount = requestedShotCount(request);
        progress.update("story_segmentation", 12);
        progress.stream("story_segmentation", "正在按叙事节拍切分剧情…\n");
        List<StoryboardSegment> segments = requestedShotCount == null
                ? normalizeSegments(modelClient.segmentStory(
                selected, state.storyBible(), sinkTo(progress)))
                : List.of(new StoryboardSegment("segment-1", 1, "完整剧情", selected, ""));
        progress.stream("story_segmentation", "剧情切分为 " + segments.size() + " 段。\n");
        boolean parallel = requestedShotCount == null && shouldSegment(selected, segments);
        StoryboardProductionRequest planningRequest = requestedShotCount == null
                ? request : withRequestedShotCount(request, requestedShotCount);
        progress.update("pseudo_planning", 20);
        List<PseudoShot> shots = parallel
                ? generatePseudoShotsParallel(state, segments, roles, progress)
                : modelClient.planPseudoShots(planningRequest, segments.getFirst(),
                state.storyBible(), roles, state.assets(), sinkTo(progress));
        shots = normalizeShots(shots, requestedShotCount);
        if (shots.isEmpty()) throw new BusinessException(50201, "未生成结构化伪分镜");
        progress.stream("pseudo_planning", "生成结构化伪分镜 " + shots.size() + " 个。\n");
        if (shots.size() > maxShots) {
            shots = List.copyOf(shots.subList(0, maxShots));
        }
        for (PseudoShot shot : shots) upsertPseudoShotRecord(state.runId(), shot);
        RunState next = copy(state, Stage.PSEUDO_REVIEW, selected, state.expansionScript(), state.assessment(),
                state.storyBible(), roles, shots, List.of(), "");
        return runStore.update(next).snapshot();
    }

    private StoryboardRunSnapshot approvePseudo(RunState state, Approval approval,
                                                StoryboardProgressListener progress) {
        List<PseudoShot> shots = approval.editedPseudoShots().isEmpty()
                ? state.pseudoShots() : normalizeShots(approval.editedPseudoShots());
        if (shots.isEmpty()) throw new BusinessException(40001, "伪分镜不能为空");
        progress.update("final_generation", 40);
        progress.stream("final_generation", "开始生成 " + shots.size() + " 个正式镜头…\n");
        List<StoryboardShot> generated = generateFinalShots(state, shots, progress);
        boolean complete = generated.size() == shots.size();
        RunState next = copy(state, complete ? Stage.FINAL_REVIEW : Stage.FINALIZING,
                state.selectedScript(), state.expansionScript(), state.assessment(), state.storyBible(), state.roles(),
                shots, generated, complete ? "" : partialFailureMessage(shots, generated));
        return runStore.update(next).snapshot();
    }

    @Override
    public StoryboardShot retryShot(String runId, String shotId) {
        RunState state = require(runId);
        PseudoShot pseudo = state.pseudoShots().stream()
                .filter(shot -> shot.shotId().equals(shotId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(40401, "伪分镜不存在: " + shotId));
        StoryboardShot shot = withPseudoIdentity(pseudo, modelClient.generateShot(
                new ShotGenerationRequest(runId, pseudo, state.storyBible(), state.assets()),
                StoryboardStreamSink.NOOP));
        List<StoryboardShot> finalShots = new ArrayList<>(state.finalShots());
        finalShots.removeIf(item -> item.shotId().equals(shot.shotId()));
        finalShots.add(shot);
        finalShots.sort(Comparator.comparing(StoryboardShot::segmentId)
                .thenComparingInt(StoryboardShot::shotOrdinal));
        boolean complete = finalShots.size() == state.pseudoShots().size();
        RunState next = copy(state, complete ? Stage.FINAL_REVIEW : Stage.FINALIZING,
                state.selectedScript(), state.expansionScript(), state.assessment(), state.storyBible(), state.roles(),
                state.pseudoShots(), finalShots, complete ? "" : state.error());
        runStore.update(next);
        upsertShotRecord(runId, pseudo, shot, "completed", "");
        return shot;
    }

    @Override
    public StoryboardRunSnapshot retryIncompleteFinalShots(String runId) {
        return retryIncompleteFinalShots(runId, StoryboardProgressListener.NOOP);
    }

    @Override
    public StoryboardRunSnapshot retryIncompleteFinalShots(String runId,
                                                           StoryboardProgressListener listener) {
        StoryboardProgressListener progress =
                listener == null ? StoryboardProgressListener.NOOP : listener;
        RunState state = require(runId);
        if (state.stage() != Stage.FINALIZING && state.stage() != Stage.FINAL_REVIEW) {
            return state.snapshot();
        }
        var done = new java.util.HashSet<String>();
        state.finalShots().forEach(shot -> done.add(shot.shotId()));
        List<PseudoShot> pending = state.pseudoShots().stream()
                .filter(shot -> !done.contains(shot.shotId()))
                .toList();
        if (pending.isEmpty()) {
            progress.stream("final_generation", "镜头已齐全，无需补跑。\n");
            RunState next = copy(state, Stage.FINAL_REVIEW, state.selectedScript(),
                    state.expansionScript(), state.assessment(), state.storyBible(), state.roles(),
                    state.pseudoShots(), state.finalShots(), "");
            return runStore.update(next).snapshot();
        }
        progress.update("final_generation", 40);
        progress.stream("final_generation", "补跑 " + pending.size() + " 个缺失镜头…\n");
        List<StoryboardShot> generated = generateFinalShots(state, pending, progress);
        List<StoryboardShot> merged = new ArrayList<>(state.finalShots());
        merged.addAll(generated);
        merged.sort(Comparator.comparing(StoryboardShot::segmentId)
                .thenComparingInt(StoryboardShot::shotOrdinal));
        boolean complete = merged.size() == state.pseudoShots().size();
        RunState next = copy(state, complete ? Stage.FINAL_REVIEW : Stage.FINALIZING,
                state.selectedScript(), state.expansionScript(), state.assessment(),
                state.storyBible(), state.roles(), state.pseudoShots(), merged,
                complete ? "" : partialFailureMessage(state.pseudoShots(), merged));
        return runStore.update(next).snapshot();
    }

    @Override
    public List<String> publishedSceneIds(String runId) {
        if (runId == null || runId.isBlank()) return List.of();
        return runStore.listShots(runId, Phase.FINAL).stream()
                .map(ShotRecord::projectSceneId)
                .filter(sceneId -> sceneId != null && !sceneId.isBlank())
                .distinct()
                .toList();
    }

    @Override
    public ShotReferenceResolution referenceAssetIds(String runId, String shotId) {
        ShotRecord finalRecord = runStore.listShots(runId, Phase.FINAL).stream()
                .filter(record -> record.shotId().equals(shotId))
                .findFirst()
                .orElse(null);
        if (finalRecord != null && !finalRecord.referenceAssetIds().isEmpty()) {
            return new ShotReferenceResolution(
                    finalRecord.referenceAssetIds(), finalRecord.referenceAssetIds(),
                    false, true, false);
        }
        ShotRecord pseudoRecord = runStore.listShots(runId, Phase.PSEUDO).stream()
                .filter(record -> record.shotId().equals(shotId))
                .findFirst()
                .orElse(null);
        if (pseudoRecord != null) {
            return new ShotReferenceResolution(
                    finalRecord == null ? pseudoRecord.referenceAssetIds()
                            : finalRecord.referenceAssetIds(),
                    pseudoRecord.referenceAssetIds(),
                    true, finalRecord != null, true);
        }
        return new ShotReferenceResolution(List.of(), List.of(),
                false, finalRecord != null, false);
    }

    @Override
    public StoryboardRunSnapshot replaceDraft(
            String runId,
            String userId,
            String projectId,
            int expectedVersion,
            List<PseudoShot> pseudoShots,
            List<StoryboardShot> finalShots) {
        RunState state = owned(runId, userId, projectId);
        if (state.version() != expectedVersion) {
            throw new BusinessException(40901, "分镜草稿版本已变化，请刷新后重试");
        }
        if (state.stage() != Stage.PSEUDO_REVIEW && state.stage() != Stage.FINAL_REVIEW) {
            throw new BusinessException(40901, "当前阶段不可编辑分镜草稿: " + state.stage());
        }
        List<PseudoShot> nextPseudo;
        List<StoryboardShot> nextFinal;
        if (state.stage() == Stage.PSEUDO_REVIEW) {
            nextPseudo = normalizeShots(pseudoShots == null ? List.of() : pseudoShots);
            nextFinal = List.of();
            runStore.replaceShots(runId, Phase.PSEUDO,
                    nextPseudo.stream().map(shot -> pseudoRecord(runId, shot)).toList());
        } else {
            nextPseudo = state.pseudoShots();
            nextFinal = normalizeFinalShots(finalShots == null ? List.of() : finalShots);
            runStore.replaceShots(runId, Phase.FINAL,
                    nextFinal.stream().map(shot -> finalRecord(runId, shot)).toList());
        }
        RunState next = copy(state, state.stage(), state.selectedScript(), state.expansionScript(),
                state.assessment(), state.storyBible(), state.roles(), nextPseudo, nextFinal, "");
        return runStore.update(next).snapshot();
    }

    @Override
    public StoryboardRunSnapshot markCompleted(
            String runId,
            String userId,
            String projectId,
            Map<String, String> sceneIdsByShotId) {
        RunState state = owned(runId, userId, projectId);
        if (state.stage() != Stage.FINAL_REVIEW) {
            throw new BusinessException(40901, "只有正式分镜审核阶段可以提交项目分镜");
        }
        Map<String, String> mapping = sceneIdsByShotId == null ? Map.of() : sceneIdsByShotId;
        // 历史数据里可能有重复 shotId（跨分段编号冲突）：按 id 去重后再校验，
        // 否则映射 Map 会塌缩，把一次正常提交误判成「映射不完整」。
        List<StoryboardShot> finalShots = new ArrayList<>();
        var seenShotIds = new java.util.HashSet<String>();
        for (StoryboardShot shot : state.finalShots()) {
            if (seenShotIds.add(shot.shotId())) finalShots.add(shot);
        }
        if (mapping.size() != finalShots.size()) {
            throw new BusinessException(40901, "项目分镜映射不完整");
        }
        for (StoryboardShot shot : finalShots) {
            String sceneId = mapping.get(shot.shotId());
            if (sceneId == null || sceneId.isBlank()) {
                throw new BusinessException(40901, "项目分镜映射不完整: " + shot.shotId());
            }
            runStore.upsertShot(finalRecord(runId, shot, sceneId));
        }
        RunState next = copy(state, Stage.COMPLETED, state.selectedScript(), state.expansionScript(),
                state.assessment(), state.storyBible(), state.roles(),
                state.pseudoShots(), finalShots, "");
        return runStore.update(next).snapshot();
    }

    @Override
    public String projectSceneId(String runId, String shotId) {
        return runStore.listShots(runId, Phase.FINAL).stream()
                .filter(record -> record.shotId().equals(shotId))
                .map(ShotRecord::projectSceneId)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("");
    }

    @Override
    public void cancel(String runId) {
        RunState state = require(runId);
        runStore.update(copy(state, Stage.CANCELLED, state.selectedScript(), state.expansionScript(), state.assessment(),
                state.storyBible(), state.roles(), state.pseudoShots(), state.finalShots(), "已取消"));
    }

    private List<PseudoShot> generatePseudoShotsParallel(RunState state, List<StoryboardSegment> segments,
                                                         List<RoleProfile> roles,
                                                         StoryboardProgressListener progress) {
        int concurrency = Math.max(1, Math.min(agentConfig.getMaxParallelAgents(), 4));
        Semaphore semaphore = new Semaphore(concurrency);
        List<CompletableFuture<List<PseudoShot>>> futures = segments.stream()
                .map(segment -> CompletableFuture.supplyAsync(() -> {
                    semaphore.acquireUninterruptibly();
                    try {
                        return modelClient.planPseudoShots(
                                requestOf(state), segment, state.storyBible(), roles, state.assets(),
                                sinkTo(progress));
                    } finally {
                        semaphore.release();
                    }
                }, executor))
                .toList();
        List<PseudoShot> merged = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        for (int index = 0; index < futures.size(); index++) {
            try {
                List<PseudoShot> segmentShots = futures.get(index).join();
                merged.addAll(segmentShots);
                progress.stream("pseudo_planning", "▸ " + segments.get(index).segmentId()
                        + " 产出 " + segmentShots.size() + " 个伪分镜\n");
            } catch (Exception e) {
                failures.put(segments.get(index).segmentId(), rootMessage(e));
                progress.stream("pseudo_planning", "▸ " + segments.get(index).segmentId()
                        + " 生成失败：" + rootMessage(e) + "\n");
            }
        }
        if (!failures.isEmpty()) {
            log.warn("storyboard pseudo-shot segment partial failure runId={} failures={}",
                    state.runId(), failures);
        }
        if (merged.isEmpty() && !failures.isEmpty()) {
            throw new BusinessException(50201, "所有剧情分段均生成失败: " + failures);
        }
        return merged;
    }

    /**
     * 伪分镜是镜头编号的真源：正式镜头强制沿用它的 shotId / segmentId，
     * 避免模型改写编号导致「正式镜头 ↔ 伪分镜 / 项目分镜映射」对不上。
     */
    private static StoryboardShot withPseudoIdentity(PseudoShot pseudo, StoryboardShot shot) {
        return new StoryboardShot(
                pseudo.shotId(), pseudo.segmentId(), shot.shotOrdinal(), shot.sceneDescription(),
                shot.shotType(), shot.cameraMovement(), shot.durationSeconds(),
                shot.imagePrompt(), shot.videoPrompt(), shot.negativePrompt(),
                shot.referenceHints());
    }

    private List<StoryboardShot> generateFinalShots(RunState state, List<PseudoShot> shots,
                                                    StoryboardProgressListener progress) {
        int concurrency = Math.max(1, Math.min(agentConfig.getMaxParallelAgents(), 4));
        Semaphore semaphore = new Semaphore(concurrency);
        List<CompletableFuture<StoryboardShot>> futures = shots.stream()
                .map(pseudo -> CompletableFuture.supplyAsync(() -> {
                    semaphore.acquireUninterruptibly();
                    try {
                        return modelClient.generateShot(new ShotGenerationRequest(
                                state.runId(), pseudo, state.storyBible(), state.assets()),
                                sinkTo(progress));
                    } finally {
                        semaphore.release();
                    }
                }, executor))
                .toList();
        List<StoryboardShot> generated = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        int finished = 0;
        for (int index = 0; index < futures.size(); index++) {
            PseudoShot pseudo = shots.get(index);
            finished++;
            try {
                StoryboardShot shot = withPseudoIdentity(pseudo, futures.get(index).join());
                generated.add(shot);
                upsertShotRecord(state.runId(), pseudo, shot, "completed", "");
                progress.stream("final_generation", "▸ " + pseudo.shotId()
                        + " 完成（" + finished + "/" + futures.size() + "）\n");
            } catch (Exception e) {
                String message = rootMessage(e);
                failures.put(pseudo.shotId(), message);
                runStore.upsertShot(new ShotRecord(
                        state.runId(), Phase.FINAL, pseudo.segmentId(), pseudo.shotId(),
                        pseudo.shotOrdinal(), "failed", "{}", pseudo.referenceAssetIds(),
                        "", message, 1));
                progress.stream("final_generation", "▸ " + pseudo.shotId()
                        + " 失败（" + finished + "/" + futures.size() + "）：" + message + "\n");
            }
            progress.update("final_generation", 40 + (50 * finished) / Math.max(1, futures.size()));
        }
        generated.sort(Comparator.comparing(StoryboardShot::segmentId)
                .thenComparingInt(StoryboardShot::shotOrdinal));
        if (!failures.isEmpty()) {
            log.warn("storyboard final shot partial failure runId={} failures={}", state.runId(), failures);
        }
        return generated;
    }

    private void upsertShotRecord(String runId, PseudoShot pseudo, StoryboardShot shot,
                                  String status, String error) {
        runStore.upsertShot(new ShotRecord(
                runId, Phase.FINAL, pseudo.segmentId(), shot.shotId(), shot.shotOrdinal(),
                status, json(shot), pseudo.referenceAssetIds(), "", error, 1));
    }

    private void upsertPseudoShotRecord(String runId, PseudoShot shot) {
        runStore.upsertShot(new ShotRecord(
                runId, Phase.PSEUDO, shot.segmentId(), shot.shotId(), shot.shotOrdinal(),
                "completed", json(shot), shot.referenceAssetIds(), "", "", 1));
    }

    private List<PseudoShot> normalizeShots(List<PseudoShot> shots) {
        if (shots == null) return List.of();
        List<PseudoShot> ordered = shots.stream()
                .sorted(Comparator.comparingInt(PseudoShot::shotOrdinal)
                        .thenComparing(PseudoShot::segmentId))
                .toList();
        List<PseudoShot> normalized = new ArrayList<>();
        // 每个分段是单独一次模型调用，模型会各写各的镜头号（如 segment-1 与 segment-3 都出现 S01），
        // shotId 是全局主键（草稿记录 / 项目分镜映射都按它对齐），这里统一去重。
        var usedShotIds = new java.util.HashSet<String>();
        for (int index = 0; index < ordered.size(); index++) {
            PseudoShot shot = ordered.get(index);
            String segmentId = shot.segmentId().isBlank() ? "segment-1" : shot.segmentId();
            String shotId = shot.shotId().isBlank() ? "" : shot.shotId();
            if (shotId.isBlank() || usedShotIds.contains(shotId)) {
                String candidate = segmentId + "-shot" + (index + 1);
                while (usedShotIds.contains(candidate)) candidate = candidate + "-x";
                shotId = candidate;
            }
            usedShotIds.add(shotId);
            normalized.add(new PseudoShot(
                    shotId, segmentId, index + 1, shot.narrativePurpose(), shot.characters(),
                    shot.location(), shot.time(), shot.conflict(), shot.action(), shot.emotion(),
                    shot.continuity(), shot.sourceExcerpt(), shot.referenceAssetIds()));
        }
        return List.copyOf(normalized);
    }

    private List<PseudoShot> normalizeShots(List<PseudoShot> shots, Integer requestedShotCount) {
        List<PseudoShot> normalized = normalizeShots(shots);
        if (requestedShotCount == null || requestedShotCount <= 0
                || normalized.size() <= requestedShotCount) {
            return normalized;
        }
        return List.copyOf(normalized.subList(0, requestedShotCount));
    }

    private List<StoryboardShot> normalizeFinalShots(List<StoryboardShot> shots) {
        if (shots == null) return List.of();
        List<StoryboardShot> ordered = shots.stream()
                .sorted(Comparator.comparingInt(StoryboardShot::shotOrdinal)
                        .thenComparing(StoryboardShot::segmentId))
                .toList();
        List<StoryboardShot> normalized = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            StoryboardShot shot = ordered.get(index);
            normalized.add(new StoryboardShot(
                    shot.shotId(), shot.segmentId(), index + 1, shot.sceneDescription(),
                    shot.shotType(), shot.cameraMovement(), shot.durationSeconds(),
                    shot.imagePrompt(), shot.videoPrompt(), shot.negativePrompt(),
                    shot.referenceHints()));
        }
        return List.copyOf(normalized);
    }

    private ShotRecord pseudoRecord(String runId, PseudoShot shot) {
        return new ShotRecord(
                runId, Phase.PSEUDO, shot.segmentId(), shot.shotId(), shot.shotOrdinal(),
                "completed", json(shot), shot.referenceAssetIds(), "", "", 1, "");
    }

    private ShotRecord finalRecord(String runId, StoryboardShot shot, String projectSceneId) {
        return new ShotRecord(
                runId, Phase.FINAL, shot.segmentId(), shot.shotId(), shot.shotOrdinal(),
                "completed", json(shot), shot.referenceHints(), "", "", 1, projectSceneId);
    }

    private ShotRecord finalRecord(String runId, StoryboardShot shot) {
        return finalRecord(runId, shot, "");
    }

    private RunState owned(String runId, String userId, String projectId) {
        RunState state = require(runId);
        if (userId == null || !userId.equals(state.userId())) {
            throw new BusinessException(40401, "分镜运行不存在或无权访问");
        }
        if (projectId != null && !projectId.isBlank()
                && state.projectId() != null && !projectId.equals(state.projectId())) {
            throw new BusinessException(40401, "分镜运行不存在或无权访问");
        }
        return state;
    }

    private List<StoryboardSegment> normalizeSegments(List<StoryboardSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return List.of(new StoryboardSegment("segment-1", 1, "完整剧情", "", ""));
        }
        List<StoryboardSegment> ordered = segments.stream()
                .sorted(Comparator.comparingInt(StoryboardSegment::ordinal)).toList();
        if (ordered.size() <= maxSegments) return ordered;
        List<StoryboardSegment> capped = new ArrayList<>(ordered.subList(0, maxSegments - 1));
        capped.add(ordered.get(ordered.size() - 1));
        return List.copyOf(capped);
    }

    private boolean shouldSegment(String script, List<StoryboardSegment> segments) {
        return segments.size() > 1 || script.length() > longScriptThreshold;
    }

    private String selectScript(RunState state, String decision) {
        if ("APPLY_EXPANSION".equalsIgnoreCase(decision) && !state.expansionScript().isBlank()) {
            return state.expansionScript();
        }
        if ("KEEP_ORIGINAL".equalsIgnoreCase(decision)) return state.originalScript();
        return state.selectedScript().isBlank() ? state.originalScript() : state.selectedScript();
    }

    private String partialFailureMessage(List<PseudoShot> expected, List<StoryboardShot> generated) {
        List<String> missing = new ArrayList<>();
        for (PseudoShot shot : expected) {
            if (generated.stream().noneMatch(item -> item.shotId().equals(shot.shotId()))) {
                missing.add(shot.shotId());
            }
        }
        return "以下镜头生成失败，可单独重试: " + String.join(", ", missing);
    }

    private RunState require(String runId) {
        return runStore.find(runId)
                .orElseThrow(() -> new BusinessException(40401, "分镜运行不存在: " + runId));
    }

    /** 把管线的流式回调接到进度监听上（文本走输出、模型 reasoning 走思考链）。 */
    private static StoryboardStreamSink sinkTo(StoryboardProgressListener progress) {
        return new StoryboardStreamSink() {
            @Override
            public void onDelta(String stage, String delta) {
                progress.stream(stage, delta);
            }

            @Override
            public void onThinking(String stage, String delta) {
                progress.thinking(stage, delta);
            }
        };
    }

    /** 把剧情诊断结论作为「思考链」展示：即使上游不回 reasoning，任务也能看到判断依据。 */
    private static void appendDiagnosisThinking(StoryboardProgressListener progress,
                                                PlotAssessment assessment) {
        if (assessment == null) return;
        if (!assessment.summary().isBlank()) {
            progress.thinking("plot_diagnosis", assessment.summary() + "\n");
        }
        if (!assessment.missingElements().isEmpty()) {
            progress.thinking("plot_diagnosis",
                    "缺口：" + String.join("；", assessment.missingElements()) + "\n");
        }
        if (!assessment.risks().isEmpty()) {
            progress.thinking("plot_diagnosis",
                    "风险：" + String.join("；", assessment.risks()) + "\n");
        }
    }

    private StoryboardProductionRequest requestOf(RunState state) {
        return new StoryboardProductionRequest(
                state.userId(), state.projectId(), state.conversationId(), state.canvasId(),
                state.originalScript(), "", state.requestedShotCount(), state.assets(), List.of());
    }

    private Integer requestedShotCount(StoryboardProductionRequest request) {
        if (request == null) return null;
        Integer count = parseShotCount(request.requestedShotCount(), SHOT_COUNT_VALUE);
        if (count == null) {
            count = parseShotCount(request.originalScript(), EXPLICIT_SHOT_COUNT);
        }
        if (count == null || count <= 0) return null;
        return Math.min(count, Math.max(1, maxShots));
    }

    private static StoryboardProductionRequest withRequestedShotCount(
            StoryboardProductionRequest request,
            int requestedShotCount
    ) {
        return new StoryboardProductionRequest(
                request.userId(), request.projectId(), request.conversationId(), request.canvasId(),
                request.originalScript(), request.creationType(), Integer.toString(requestedShotCount),
                request.assets(), request.roleHints());
    }

    private static Integer parseShotCount(String text, Pattern pattern) {
        if (text == null || text.isBlank()) return null;
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return null;
        int parsed = parseCountValue(matcher.group(1));
        return parsed > 0 ? parsed : null;
    }

    private static int parseCountValue(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        if (raw.matches("\\d+")) return Integer.parseInt(raw);
        int total = 0;
        int current = 0;
        for (char c : raw.toCharArray()) {
            int digit = switch (c) {
                case '一' -> 1;
                case '二' -> 2;
                case '三' -> 3;
                case '四' -> 4;
                case '五' -> 5;
                case '六' -> 6;
                case '七' -> 7;
                case '八' -> 8;
                case '九' -> 9;
                default -> -1;
            };
            if (digit >= 0) {
                current = digit;
            } else if (c == '十') {
                total += (current == 0 ? 1 : current) * 10;
                current = 0;
            } else if (c == '百') {
                total += (current == 0 ? 1 : current) * 100;
                current = 0;
            } else {
                return 0;
            }
        }
        return total + current;
    }

    private RunState copy(RunState state, Stage stage, String selectedScript, String expansionScript,
                          StoryboardProductionModels.PlotAssessment assessment,
                          StoryboardProductionModels.StoryBible storyBible,
                          List<RoleProfile> roles, List<PseudoShot> pseudoShots,
                          List<StoryboardShot> finalShots, String error) {
        return new RunState(
                state.runId(), state.userId(), state.projectId(), state.conversationId(),
                state.canvasId(), state.originalScript(), state.requestedShotCount(),
                selectedScript, expansionScript,
                stage, state.version() + 1,
                assessment, storyBible, roles, state.assets(), pseudoShots, finalShots,
                error, state.createdAt(), OffsetDateTime.now());
    }

    private StoryboardRunSnapshot fail(String message) {
        throw new BusinessException(40901, message);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
