package com.moon.moonagent.dto.response;

import java.util.List;

/** 会话资产组绑定响应。 */
public record ConversationAssetGroupVO(
        String groupId,
        String snapshotId,
        long groupVersion,
        String contentHash,
        String target,
        boolean stale,
        List<SnapshotMediaVO> media
) {
    public record SnapshotMediaVO(String type, String role, String contentUrl) {
    }
}
