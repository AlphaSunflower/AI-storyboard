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

@Service
@RequiredArgsConstructor
public class StoryboardProductionServiceImpl implements StoryboardProductionService {

    private static final Logger log = LoggerFactory.getLogger(StoryboardProductionServiceImpl.class);
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
        if (request == null || request.originalScript().isBlank()) {
            throw new BusinessException(40001, "剧情不能为空");
        }
        boolean multiSegmentCandidate = request.originalScript().length() > longScriptThreshold;
        var roles = roleRegistry.resolve(request.originalScript(), request.roleHints(), multiSegmentCandidate);
        var assessment = modelClient.assessPlot(request, request.assets());
        var optimization = modelClient.optimizeScript(request, assessment, roles, request.assets());
        OffsetDateTime now = OffsetDateTime.now();
        RunState state = new RunState(
                UUID.randomUUID().toString(), request.userId(), request.projectId(),
                request.conversationId(), request.canvasId(), request.originalScript(),
                optimization.optimizedScript(), optimization.expansionScript(), Stage.PLOT_REVIEW, 1,
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
        boolean multiSegmentCandidate = request.originalScript().length() > longScriptThreshold;
        var roles = roleRegistry.resolve(request.originalScript(), request.roleHints(), multiSegmentCandidate);

        progress.update("script_optimization", 8);
        PlotAssessment generationAssessment = new PlotAssessment(
                false,
                List.of("尚未形成可直接进入分镜的完整剧情或展示脚本"),
                List.of(),
                "用户提供的是创作需求，需要先补全为完整剧情或展示脚本",
                "保留用户核心设定，生成完整、连续、可直接进入结构化分镜的剧情或展示脚本");
        OptimizationResult generation = modelClient.optimizeScript(
                request, generationAssessment, roles, request.assets());
        String generatedScript = firstNonBlank(
                generation.expansionScript(), generation.optimizedScript(), request.originalScript());
        progress.plotReady(generatedScript);
        StoryboardProductionRequest generatedRequest = withOriginalScript(request, generatedScript);

        progress.update("plot_diagnosis", 25);
        PlotAssessment assessment = modelClient.assessPlot(generatedRequest, request.assets());

        ScriptRepairResult repair;
        if (assessment.complete()) {
            repair = new ScriptRepairResult(
                    generatedScript, generation.storyBible(), List.of());
        } else {
            progress.update("script_repair", 40);
            repair = repairScriptWithOneRetry(generatedRequest, assessment, roles);
        }
        String selectedScript = repair.revisedScript().isBlank()
                ? generatedScript : repair.revisedScript();
        StoryBible storyBible = hasStoryBible(repair.storyBible())
                ? repair.storyBible() : generation.storyBible();

        OffsetDateTime now = OffsetDateTime.now();
        RunState state = new RunState(
                UUID.randomUUID().toString(), request.userId(), request.projectId(),
                request.conversationId(), request.canvasId(), request.originalScript(),
                selectedScript, "", Stage.FINALIZING, 1,
                assessment, storyBible, roles, request.assets(),
                List.of(), List.of(), "", now, now);
        state = runStore.insert(state);

        progress.update("story_segmentation", 55);
        List<StoryboardSegment> segments = normalizeSegments(
                modelClient.segmentStory(selectedScript, storyBible));
        progress.update("pseudo_planning", 65);
        boolean parallel = shouldSegment(selectedScript, segments);
        List<PseudoShot> shots = parallel
                ? generatePseudoShotsParallel(state, segments, roles)
                : modelClient.planPseudoShots(
                requestOf(state), segments.getFirst(), storyBible, roles, state.assets());
        shots = normalizeShots(shots);
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
        List<StoryboardShot> generated = generateFinalShots(state, shots);
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
            List<RoleProfile> roles) {
        RuntimeException firstFailure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                ScriptRepairResult result = modelClient.repairScript(
                        request, assessment, roles, request.assets());
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
        RunState state = require(runId);
        if (targetStage == Stage.PLOT_REVIEW) {
            var request = requestOf(state);
            var optimization = modelClient.optimizeScript(
                    request, state.assessment(), state.roles(), state.assets(), instruction);
            RunState revised = copy(state, Stage.PLOT_REVIEW, optimization.optimizedScript(),
                    optimization.expansionScript(), state.assessment(), optimization.storyBible(),
                    state.roles(), List.of(), List.of(), "");
            return runStore.update(revised).snapshot();
        }
        if (targetStage == Stage.PSEUDO_REVIEW) {
            List<PseudoShot> shots = modelClient.revisePseudoShots(
                    state.pseudoShots(), instruction == null ? "" : instruction, shotId,
                    state.storyBible(), state.assets());
            shots = normalizeShots(shots);
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
        RunState state = require(runId);
        if (approval == null || approval.stage() == null) {
            throw new BusinessException(40001, "确认阶段不能为空");
        }
        if (state.stage() != approval.stage()) {
            throw new BusinessException(40901, "分镜运行阶段已变化，请刷新后重试");
        }
        return approval.stage() == Stage.PLOT_REVIEW
                ? approvePlot(state, approval)
                : approval.stage() == Stage.PSEUDO_REVIEW
                ? approvePseudo(state, approval)
                : fail("当前阶段不可确认: " + approval.stage());
    }

    @Override
    public StoryboardRunSnapshot approveToFinal(String runId, Approval plotApproval) {
        StoryboardRunSnapshot pseudo = approve(runId, plotApproval);
        return approve(pseudo.runId(),
                new Approval(Stage.PSEUDO_REVIEW, "APPROVE", "", List.of(), List.of()));
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

    private StoryboardRunSnapshot approvePlot(RunState state, Approval approval) {
        String selected = selectScript(state, approval.decision());
        var roles = approval.roles().isEmpty() ? state.roles() : approval.roles();
        List<StoryboardSegment> segments = modelClient.segmentStory(selected, state.storyBible());
        segments = normalizeSegments(segments);
        boolean parallel = shouldSegment(selected, segments);
        List<PseudoShot> shots = parallel
                ? generatePseudoShotsParallel(state, segments, roles)
                : modelClient.planPseudoShots(requestOf(state), segments.getFirst(), state.storyBible(), roles, state.assets());
        shots = normalizeShots(shots);
        if (shots.isEmpty()) throw new BusinessException(50201, "未生成结构化伪分镜");
        if (shots.size() > maxShots) {
            shots = List.copyOf(shots.subList(0, maxShots));
        }
        for (PseudoShot shot : shots) upsertPseudoShotRecord(state.runId(), shot);
        RunState next = copy(state, Stage.PSEUDO_REVIEW, selected, state.expansionScript(), state.assessment(),
                state.storyBible(), roles, shots, List.of(), "");
        return runStore.update(next).snapshot();
    }

    private StoryboardRunSnapshot approvePseudo(RunState state, Approval approval) {
        List<PseudoShot> shots = approval.editedPseudoShots().isEmpty()
                ? state.pseudoShots() : normalizeShots(approval.editedPseudoShots());
        if (shots.isEmpty()) throw new BusinessException(40001, "伪分镜不能为空");
        List<StoryboardShot> generated = generateFinalShots(state, shots);
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
        StoryboardShot shot = modelClient.generateShot(new ShotGenerationRequest(
                runId, pseudo, state.storyBible(), state.assets()));
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
        if (mapping.size() != state.finalShots().size()) {
            throw new BusinessException(40901, "项目分镜映射不完整");
        }
        for (StoryboardShot shot : state.finalShots()) {
            String sceneId = mapping.get(shot.shotId());
            if (sceneId == null || sceneId.isBlank()) {
                throw new BusinessException(40901, "项目分镜映射不完整: " + shot.shotId());
            }
            runStore.upsertShot(finalRecord(runId, shot, sceneId));
        }
        RunState next = copy(state, Stage.COMPLETED, state.selectedScript(), state.expansionScript(),
                state.assessment(), state.storyBible(), state.roles(),
                state.pseudoShots(), state.finalShots(), "");
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
                                                         List<RoleProfile> roles) {
        int concurrency = Math.max(1, Math.min(agentConfig.getMaxParallelAgents(), 4));
        Semaphore semaphore = new Semaphore(concurrency);
        List<CompletableFuture<List<PseudoShot>>> futures = segments.stream()
                .map(segment -> CompletableFuture.supplyAsync(() -> {
                    semaphore.acquireUninterruptibly();
                    try {
                        return modelClient.planPseudoShots(
                                requestOf(state), segment, state.storyBible(), roles, state.assets());
                    } finally {
                        semaphore.release();
                    }
                }, executor))
                .toList();
        List<PseudoShot> merged = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        for (int index = 0; index < futures.size(); index++) {
            try {
                merged.addAll(futures.get(index).join());
            } catch (Exception e) {
                failures.put(segments.get(index).segmentId(), rootMessage(e));
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

    private List<StoryboardShot> generateFinalShots(RunState state, List<PseudoShot> shots) {
        int concurrency = Math.max(1, Math.min(agentConfig.getMaxParallelAgents(), 4));
        Semaphore semaphore = new Semaphore(concurrency);
        List<CompletableFuture<StoryboardShot>> futures = shots.stream()
                .map(pseudo -> CompletableFuture.supplyAsync(() -> {
                    semaphore.acquireUninterruptibly();
                    try {
                        return modelClient.generateShot(new ShotGenerationRequest(
                                state.runId(), pseudo, state.storyBible(), state.assets()));
                    } finally {
                        semaphore.release();
                    }
                }, executor))
                .toList();
        List<StoryboardShot> generated = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        for (int index = 0; index < futures.size(); index++) {
            PseudoShot pseudo = shots.get(index);
            try {
                StoryboardShot shot = futures.get(index).join();
                generated.add(shot);
                upsertShotRecord(state.runId(), pseudo, shot, "completed", "");
            } catch (Exception e) {
                String message = rootMessage(e);
                failures.put(pseudo.shotId(), message);
                runStore.upsertShot(new ShotRecord(
                        state.runId(), Phase.FINAL, pseudo.segmentId(), pseudo.shotId(),
                        pseudo.shotOrdinal(), "failed", "{}", pseudo.referenceAssetIds(),
                        "", message, 1));
            }
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
        for (int index = 0; index < ordered.size(); index++) {
            PseudoShot shot = ordered.get(index);
            String segmentId = shot.segmentId().isBlank() ? "segment-1" : shot.segmentId();
            String shotId = shot.shotId().isBlank() ? "shot-" + (index + 1) : shot.shotId();
            normalized.add(new PseudoShot(
                    shotId, segmentId, index + 1, shot.narrativePurpose(), shot.characters(),
                    shot.location(), shot.time(), shot.conflict(), shot.action(), shot.emotion(),
                    shot.continuity(), shot.sourceExcerpt(), shot.referenceAssetIds()));
        }
        return List.copyOf(normalized);
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

    private StoryboardProductionRequest requestOf(RunState state) {
        return new StoryboardProductionRequest(
                state.userId(), state.projectId(), state.conversationId(), state.canvasId(),
                state.originalScript(), "", "", state.assets(), List.of());
    }

    private RunState copy(RunState state, Stage stage, String selectedScript, String expansionScript,
                          StoryboardProductionModels.PlotAssessment assessment,
                          StoryboardProductionModels.StoryBible storyBible,
                          List<RoleProfile> roles, List<PseudoShot> pseudoShots,
                          List<StoryboardShot> finalShots, String error) {
        return new RunState(
                state.runId(), state.userId(), state.projectId(), state.conversationId(),
                state.canvasId(), state.originalScript(), selectedScript, expansionScript,
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
