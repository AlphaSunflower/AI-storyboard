package com.moon.moonagent.ai.storyboard.production;

import com.moon.moonagent.ai.storyboard.production.StoryboardChangeLog.ShotChange;
import com.moon.moonagent.ai.storyboard.production.StoryboardProductionModels.PseudoShot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 改动日志的字段级 diff：只记真正变了的字段，值没变就不产生噪音。
 *
 * <p>PseudoShot 参数顺序：shotId, segmentId, shotOrdinal, narrativePurpose, characters,
 * location, time, conflict, action, emotion, continuity, sourceExcerpt, referenceAssetIds。
 */
class StoryboardChangeLogTest {

    private static PseudoShot shot(String id, int ordinal, String action) {
        return new PseudoShot(id, "segment-1", ordinal, "", List.of(), "旧仓库", "夜", "",
                action, "", List.of(), List.of(), List.of());
    }

    private static PseudoShot withAssets(String id, int ordinal, String action, List<String> assets) {
        return new PseudoShot(id, "segment-1", ordinal, "", List.of(), "旧仓库", "夜", "",
                action, "", List.of(), List.of(), assets);
    }

    @Test
    void recordsOneRowPerChangedField() {
        PseudoShot before = withAssets("shot-1", 1, "原始画面", List.of("asset-1"));
        PseudoShot afterOnlyAction = withAssets("shot-1", 1, "改后画面", List.of("asset-1"));
        // 时间也从「夜」改成「深夜」，与画面、资产合计三个字段变化
        PseudoShot afterWithTime = new PseudoShot("shot-1", "segment-1", 1, "", List.of(),
                "旧仓库", "深夜", "", "改后画面", "", List.of(), List.of(),
                List.of("asset-1", "asset-2"));

        List<ShotChange> changes = StoryboardChangeLog.diff("run-1", "user",
                List.of(before), List.of(afterWithTime));

        assertThat(changes).extracting(ShotChange::field).containsExactly("画面", "时间", "资产");
        assertThat(changes).allSatisfy(change -> {
            assertThat(change.kind()).isEqualTo("UPDATE");
            assertThat(change.source()).isEqualTo("user");
            assertThat(change.runId()).isEqualTo("run-1");
        });
        assertThat(changes.get(0).before()).isEqualTo("原始画面");
        assertThat(changes.get(0).after()).isEqualTo("改后画面");
        assertThat(changes.get(2).after()).isEqualTo("asset-1、asset-2");

        // 只改画面时，只有一条
        assertThat(StoryboardChangeLog.diff("run-1", "user",
                List.of(before), List.of(afterOnlyAction)))
                .extracting(ShotChange::field).containsExactly("画面");
    }

    @Test
    void unchangedDraftProducesNoChanges() {
        PseudoShot same = shot("shot-1", 1, "画面");

        assertThat(StoryboardChangeLog.diff("run-1", "user", List.of(same), List.of(same))).isEmpty();
    }

    @Test
    void addedAndRemovedShotsAreRecordedAsInsertAndDelete() {
        PseudoShot kept = shot("shot-1", 1, "保留");
        PseudoShot added = shot("shot-2", 2, "新增");
        PseudoShot removed = shot("shot-3", 3, "被删");

        List<ShotChange> changes = StoryboardChangeLog.diff("run-1", "agent",
                List.of(kept, removed), List.of(kept, added));

        assertThat(changes).hasSize(2);
        assertThat(changes).extracting(ShotChange::kind).containsExactlyInAnyOrder("INSERT", "DELETE");
        assertThat(changes).extracting(ShotChange::shotId).containsExactlyInAnyOrder("shot-2", "shot-3");
    }

    @Test
    void blankAndNullValuesAreTreatedAsEqual() {
        PseudoShot before = new PseudoShot("shot-1", "segment-1", 1, "", List.of(), "  ", "夜", "",
                "画面", "", List.of(), List.of(), List.of());
        PseudoShot after = new PseudoShot("shot-1", "segment-1", 1, "", List.of(), "", "夜", "",
                "画面", "", List.of(), List.of(), List.of());

        assertThat(StoryboardChangeLog.diff("run-1", "user", List.of(before), List.of(after)))
                .isEmpty();
    }
}
