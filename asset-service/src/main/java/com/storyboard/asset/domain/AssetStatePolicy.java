package com.storyboard.asset.domain;

import java.util.Set;

/** 资产生命周期的纯状态规则。 */
public final class AssetStatePolicy {

    private static final Set<String> RESTORABLE = Set.of("TRASHED", "PURGE_PENDING", "PURGE_BLOCKED");

    private AssetStatePolicy() {
    }

    public static boolean canRestore(String status) {
        return status != null && RESTORABLE.contains(status);
    }

    public static boolean canPurge(String status) {
        return "TRASHED".equals(status) || "PURGE_PENDING".equals(status) || "PURGE_BLOCKED".equals(status);
    }
}
