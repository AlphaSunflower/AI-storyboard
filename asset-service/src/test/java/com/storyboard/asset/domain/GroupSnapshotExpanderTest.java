package com.storyboard.asset.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GroupSnapshotExpanderTest {

    @Test
    void deduplicatesByMediaAndSortsByOrder() {
        var expanded = GroupSnapshotExpander.expand(List.of(
                new GroupSnapshotExpander.Item("m2", "a2", "b2", "videos/2.mp4", "VIDEO", "REFERENCE", 2),
                new GroupSnapshotExpander.Item("m1", "a1", "b1", "images/1.png", "IMAGE", "FIRST_FRAME", 1),
                new GroupSnapshotExpander.Item("m2", "a2", "b2", "videos/2.mp4", "VIDEO", "REFERENCE", 3)
        ), 9, 3, 3);

        assertThat(expanded).extracting(GroupSnapshotExpander.Item::mediaId)
                .containsExactly("m1", "m2");
        assertThat(expanded).extracting(GroupSnapshotExpander.Item::sortOrder)
                .containsExactly(0, 1);
        assertThat(expanded.getFirst().role()).isEqualTo("FIRST_FRAME");
    }

    @Test
    void rejectsGroupsThatExceedLimitsInsteadOfSilentTruncation() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> GroupSnapshotExpander.expand(List.of(
                new GroupSnapshotExpander.Item("m1", "a1", "b1", "images/1.png", "IMAGE", "REFERENCE", 1),
                new GroupSnapshotExpander.Item("m2", "a2", "b2", "images/2.png", "IMAGE", "REFERENCE", 2)
        ), 1, 3, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("图片");
    }
}
