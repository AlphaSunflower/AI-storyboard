package com.moon.moonagent.ai.agent.agentscope;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopeBatchImageSlotsTest {

    @Test
    void resultUrlsStayInPromptSlotOrderWhileContentUsesCompletionOrder() {
        OffsetDateTime base = OffsetDateTime.parse("2026-09-24T10:00:00+08:00");
        List<AgentScopeBatchImageSlots.Slot> slots = List.of(
                AgentScopeBatchImageSlots.Slot.pending(0, "第一张")
                        .complete("/one.png", "asset-1", base.plusSeconds(5)),
                AgentScopeBatchImageSlots.Slot.pending(1, "第二张")
                        .complete("/two.png", "asset-2", base.plusSeconds(1)),
                AgentScopeBatchImageSlots.Slot.pending(2, "第三张"));

        var snapshot = AgentScopeBatchImageSlots.snapshot(slots, false);

        assertThat(snapshot.resultUrls()).containsExactly("/one.png", "/two.png");
        assertThat(snapshot.assetIds()).containsExactly("asset-1", "asset-2");
        assertThat(snapshot.content()).isEqualTo(
                "![生成图片](/two.png)\n\n![生成图片](/one.png)");
    }

    @Test
    void partialStatusRequiresAtLeastOneSuccessAndOneFailure() {
        List<AgentScopeBatchImageSlots.Slot> partial = List.of(
                AgentScopeBatchImageSlots.Slot.pending(0, "第一张")
                        .complete("/one.png", "asset-1", OffsetDateTime.now()),
                AgentScopeBatchImageSlots.Slot.pending(1, "第二张")
                        .fail("审核失败", OffsetDateTime.now()));
        List<AgentScopeBatchImageSlots.Slot> failed = List.of(
                AgentScopeBatchImageSlots.Slot.pending(0, "第一张")
                        .fail("审核失败", OffsetDateTime.now()));

        assertThat(AgentScopeBatchImageSlots.snapshot(partial, true).status()).isEqualTo("partial");
        assertThat(AgentScopeBatchImageSlots.snapshot(failed, true).status()).isEqualTo("failed");
    }

    @Test
    void retryRequestOnlyCarriesFailedOrMissingSlots() {
        List<AgentScopeBatchImageSlots.Slot> slots = List.of(
                AgentScopeBatchImageSlots.Slot.pending(2, "第三张")
                        .complete("/three.png", "asset-3", OffsetDateTime.now()),
                AgentScopeBatchImageSlots.Slot.pending(0, "第一张")
                        .fail("超时", OffsetDateTime.now()),
                AgentScopeBatchImageSlots.Slot.pending(1, "第二张"));

        assertThat(AgentScopeBatchImageSlots.failedOrMissingIndices(slots))
                .containsExactly(0, 1);
        assertThat(AgentScopeBatchImageSlots.promptsAt(slots, List.of(0, 1)))
                .containsExactly("第一张", "第二张");
    }

    @Test
    void completedRetrySlotsMergeBackByOriginalIndex() {
        List<AgentScopeBatchImageSlots.Slot> parent = List.of(
                AgentScopeBatchImageSlots.Slot.pending(0, "第一张")
                        .complete("/one.png", "asset-1", OffsetDateTime.now()),
                AgentScopeBatchImageSlots.Slot.pending(1, "第二张")
                        .fail("超时", OffsetDateTime.now()));
        List<AgentScopeBatchImageSlots.Slot> retry = List.of(
                AgentScopeBatchImageSlots.Slot.pending(1, "第二张")
                        .complete("/two-retry.png", "asset-2r", OffsetDateTime.now().plusSeconds(1)));

        var merged = AgentScopeBatchImageSlots.merge(parent, retry);

        assertThat(merged).extracting(AgentScopeBatchImageSlots.Slot::index)
                .containsExactly(0, 1);
        assertThat(merged.get(1).status()).isEqualTo("completed");
        assertThat(merged.get(1).url()).isEqualTo("/two-retry.png");
        assertThat(AgentScopeBatchImageSlots.snapshot(merged, true).status()).isEqualTo("completed");
    }

    @Test
    void slotMapsRoundTripAndRetryKeepsOriginalIndices() {
        List<AgentScopeBatchImageSlots.Slot> initial =
                AgentScopeBatchImageSlots.initial(List.of("重试A", "重试B"), List.of(2, 4));
        List<AgentScopeBatchImageSlots.Slot> restored =
                AgentScopeBatchImageSlots.fromMaps(AgentScopeBatchImageSlots.toMaps(initial));

        assertThat(initial).extracting(AgentScopeBatchImageSlots.Slot::index)
                .containsExactly(2, 4);
        assertThat(restored).extracting(AgentScopeBatchImageSlots.Slot::prompt)
                .containsExactly("重试A", "重试B");
        assertThat(restored).extracting(AgentScopeBatchImageSlots.Slot::index)
                .containsExactly(2, 4);
    }

    @Test
    void slotMapsRoundTripSceneLabelsForTaskDrawer() {
        List<AgentScopeBatchImageSlots.Slot> initial =
                AgentScopeBatchImageSlots.initial(List.of("画面一", "画面二"), List.of(0, 1),
                        List.of("分镜1", "分镜2"));
        List<AgentScopeBatchImageSlots.Slot> restored =
                AgentScopeBatchImageSlots.fromMaps(AgentScopeBatchImageSlots.toMaps(initial));

        assertThat(restored).extracting(AgentScopeBatchImageSlots.Slot::label)
                .containsExactly("分镜1", "分镜2");
    }

    @Test
    void retryLabelsFollowTheSelectedSlotIndices() {
        List<AgentScopeBatchImageSlots.Slot> slots = List.of(
                AgentScopeBatchImageSlots.Slot.pending(0, "分镜1", "画面一"),
                AgentScopeBatchImageSlots.Slot.pending(1, "分镜2", "画面二"),
                AgentScopeBatchImageSlots.Slot.pending(2, "分镜3", "画面三"));

        assertThat(AgentScopeBatchImageSlots.labelsAt(slots, List.of(1, 2)))
                .containsExactly("分镜2", "分镜3");
    }
}
