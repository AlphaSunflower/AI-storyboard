package com.moon.moonagent.ai.agent.agentscope;

import java.util.List;

record AgentScopeAssetGroupRef(
        String groupId,
        String snapshotId,
        String name,
        String description,
        long version,
        List<FileRef> files
) {
    record FileRef(
            String assetId,
            String assetName,
            String assetDescription,
            String mediaId,
            String type,
            String role,
            String fileName,
            String mimeType,
            String objectKey,
            String constraintText,
            String contentUrl
    ) {
    }
}
