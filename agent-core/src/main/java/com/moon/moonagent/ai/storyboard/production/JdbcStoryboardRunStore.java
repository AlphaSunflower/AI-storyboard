package com.moon.moonagent.ai.storyboard.production;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.storyboard.StoryboardShot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.AssetRef;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.Phase;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.PlotAssessment;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.PseudoShot;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.RoleProfile;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.RunState;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.ShotRecord;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.Stage;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.StoryBible;
import com.moon.moonagent.entity.StoryboardRunEntity;
import com.moon.moonagent.entity.StoryboardShotRecordEntity;
import com.moon.moonagent.mapper.StoryboardRunMapper;
import com.moon.moonagent.mapper.StoryboardShotRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JdbcStoryboardRunStore implements StoryboardRunStore {

    private final StoryboardRunMapper runMapper;
    private final StoryboardShotRecordMapper shotMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public RunState insert(RunState state) {
        StoryboardRunEntity entity = toEntity(state);
        runMapper.insert(entity);
        return find(entity.getId()).orElseThrow();
    }

    @Override
    public RunState update(RunState state) {
        StoryboardRunEntity entity = toEntity(state);
        entity.setId(state.runId());
        entity.setUpdatedAt(OffsetDateTime.now());
        runMapper.updateById(entity);
        return find(state.runId()).orElseThrow();
    }

    @Override
    public Optional<RunState> find(String runId) {
        StoryboardRunEntity entity = runMapper.selectById(runId);
        if (entity == null) {
            String normalized = canonicalRunId(runId);
            if (!normalized.equals(runId)) entity = runMapper.selectById(normalized);
        }
        return entity == null ? Optional.empty() : Optional.of(toState(entity));
    }

    static String canonicalRunId(String runId) {
        if (runId == null || !runId.matches("[0-9a-fA-F]{32}")) return runId;
        return runId.substring(0, 8) + "-"
                + runId.substring(8, 12) + "-"
                + runId.substring(12, 16) + "-"
                + runId.substring(16, 20) + "-"
                + runId.substring(20);
    }

    @Override
    public Optional<RunState> findLatest(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return Optional.empty();
        StoryboardRunEntity entity = runMapper.selectOne(
                new LambdaQueryWrapper<StoryboardRunEntity>()
                        .eq(StoryboardRunEntity::getConversationId, conversationId)
                        .orderByDesc(StoryboardRunEntity::getUpdatedAt)
                        .last("LIMIT 1"));
        return entity == null ? Optional.empty() : Optional.of(toState(entity));
    }

    @Override
    public void upsertShot(ShotRecord record) {
        StoryboardShotRecordEntity existing = shotMapper.selectOne(new LambdaQueryWrapper<StoryboardShotRecordEntity>()
                .eq(StoryboardShotRecordEntity::getRunId, record.runId())
                .eq(StoryboardShotRecordEntity::getPhase, record.phase().name())
                .eq(StoryboardShotRecordEntity::getShotId, record.shotId())
                .last("LIMIT 1"));
        StoryboardShotRecordEntity entity = existing == null
                ? new StoryboardShotRecordEntity() : existing;
        entity.setRunId(record.runId());
        entity.setPhase(record.phase().name());
        entity.setSegmentId(record.segmentId());
        entity.setShotId(record.shotId());
        entity.setShotOrdinal(record.shotOrdinal());
        entity.setStatus(record.status());
        entity.setPayload(record.payload());
        entity.setReferenceAssetIds(writeJson(record.referenceAssetIds()));
        entity.setProviderTaskId(record.taskId());
        entity.setError(record.error());
        entity.setAttempt(record.attempt());
        entity.setProjectSceneId(record.projectSceneId());
        if (existing == null) shotMapper.insert(entity);
        else shotMapper.updateById(entity);
    }

    @Override
    public List<ShotRecord> listShots(String runId, Phase phase) {
        return shotMapper.selectList(new LambdaQueryWrapper<StoryboardShotRecordEntity>()
                        .eq(StoryboardShotRecordEntity::getRunId, runId)
                        .eq(StoryboardShotRecordEntity::getPhase, phase.name())
                        .orderByAsc(StoryboardShotRecordEntity::getShotOrdinal))
                .stream()
                .map(entity -> new ShotRecord(
                        entity.getRunId(), Phase.valueOf(entity.getPhase()), entity.getSegmentId(),
                        entity.getShotId(), entity.getShotOrdinal(), entity.getStatus(),
                        entity.getPayload(), readStringList(entity.getReferenceAssetIds()),
                        entity.getProviderTaskId(), entity.getError(),
                        entity.getAttempt() == null ? 0 : entity.getAttempt(),
                        entity.getProjectSceneId()))
                .toList();
    }

    @Override
    public void replaceShots(String runId, StoryboardProductionModels.Phase phase,
                             List<ShotRecord> records) {
        shotMapper.delete(new LambdaQueryWrapper<StoryboardShotRecordEntity>()
                .eq(StoryboardShotRecordEntity::getRunId, runId)
                .eq(StoryboardShotRecordEntity::getPhase, phase.name()));
        if (records != null) records.forEach(this::upsertShot);
    }

    private StoryboardRunEntity toEntity(RunState state) {
        StoryboardRunEntity entity = new StoryboardRunEntity();
        entity.setId(state.runId());
        entity.setUserId(state.userId());
        entity.setProjectId(state.projectId());
        entity.setConversationId(state.conversationId());
        entity.setCanvasId(state.canvasId());
        entity.setOriginalScript(state.originalScript());
        entity.setRequestedShotCount(state.requestedShotCount());
        entity.setSelectedScript(state.selectedScript());
        entity.setExpansionScript(state.expansionScript());
        entity.setStage(state.stage().name());
        entity.setVersion(state.version());
        entity.setPlotAssessment(writeJson(state.assessment()));
        entity.setStoryBible(writeJson(state.storyBible()));
        entity.setRoles(writeJson(state.roles()));
        entity.setAssets(writeJson(state.assets()));
        entity.setPseudoShots(writeJson(state.pseudoShots()));
        entity.setFinalShots(writeJson(state.finalShots()));
        entity.setError(state.error());
        entity.setCreatedAt(state.createdAt());
        entity.setUpdatedAt(state.updatedAt());
        return entity;
    }

    private RunState toState(StoryboardRunEntity entity) {
        return new RunState(
                entity.getId(),
                entity.getUserId(),
                entity.getProjectId(),
                entity.getConversationId(),
                entity.getCanvasId(),
                entity.getOriginalScript(),
                entity.getRequestedShotCount(),
                entity.getSelectedScript(),
                entity.getExpansionScript(),
                Stage.valueOf(entity.getStage()),
                entity.getVersion() == null ? 1 : entity.getVersion(),
                read(entity.getPlotAssessment(), PlotAssessment.class,
                        new PlotAssessment(true, List.of(), List.of(), "", "")),
                read(entity.getStoryBible(), StoryBible.class, StoryBible.empty()),
                readList(entity.getRoles(), new TypeReference<List<RoleProfile>>() {}),
                readList(entity.getAssets(), new TypeReference<List<AssetRef>>() {}),
                readList(entity.getPseudoShots(), new TypeReference<List<PseudoShot>>() {}),
                readList(entity.getFinalShots(), new TypeReference<List<StoryboardShot>>() {}),
                entity.getError(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private String writeJson(Object value) {
        try {
            return value == null ? "{}" : objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("storyboard state serialize failed", e);
        }
    }

    private <T> T read(String json, Class<T> type, T fallback) {
        if (json == null || json.isBlank()) return fallback;
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            return fallback;
        }
    }

    private <T> List<T> readList(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> readStringList(String json) {
        return readList(json, new TypeReference<>() {});
    }

}
