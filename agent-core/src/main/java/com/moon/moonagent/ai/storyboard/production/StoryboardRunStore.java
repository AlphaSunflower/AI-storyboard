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
}
