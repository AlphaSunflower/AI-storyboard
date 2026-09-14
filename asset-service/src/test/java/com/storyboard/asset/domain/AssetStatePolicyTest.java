package com.storyboard.asset.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssetStatePolicyTest {

    @Test
    void trashedAssetCanRestore() {
        assertThat(AssetStatePolicy.canRestore("TRASHED")).isTrue();
    }

    @Test
    void purgePendingAssetCanRestoreBeforeObjectDeletion() {
        assertThat(AssetStatePolicy.canRestore("PURGE_PENDING")).isTrue();
    }

    @Test
    void purgedAssetCannotRestore() {
        assertThat(AssetStatePolicy.canRestore("PURGED")).isFalse();
    }
}
