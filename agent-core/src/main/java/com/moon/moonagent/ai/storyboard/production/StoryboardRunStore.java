package com.moon.moonagent.ai.storyboard.production;

import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.RunState;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.ShotRecord;

import java.util.List;
import java.util.Optional;

public interface StoryboardRunStore {

    RunState insert(RunState state);

    RunState update(RunState state);

    Optional<RunState> find(String runId);

    Optional<RunState> findLatest(String conversationId);

    void upsertShot(ShotRecord record);

    List<ShotRecord> listShots(String runId, StoryboardProductionModels.Phase phase);

    default void replaceShots(String runId, StoryboardProductionModels.Phase phase,
                              List<ShotRecord> records) {
        if (records == null) return;
        records.forEach(this::upsertShot);
    }

    /** 追加字段级改动日志（变更审阅数据源）。默认空实现，测试假件只需关心主线行为。 */
    default void appendChanges(List<StoryboardChangeLog.ShotChange> changes) {
    }

    /** 按时间正序读改动日志。默认空列表。 */
    default List<StoryboardChangeLog.ShotChange> listChanges(String runId) {
        return List.of();
    }
}
