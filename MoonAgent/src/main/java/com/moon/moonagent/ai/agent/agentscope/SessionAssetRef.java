package com.moon.moonagent.ai.agent.agentscope;

import java.time.OffsetDateTime;

public record SessionAssetRef(
        String assetId,
        String type,
        String url,
        String sourceKind,
        OffsetDateTime createdAt,
        String label
) {
}
