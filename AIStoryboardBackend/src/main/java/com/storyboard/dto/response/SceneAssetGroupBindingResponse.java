package com.storyboard.dto.response;

import java.util.List;

public record SceneAssetGroupBindingResponse(
        String groupId,
        String snapshotId,
        long groupVersion,
        String contentHash,
        String target,
        String purpose,
        boolean stale,
        List<Media> media
) {
    public record Media(String type, String role, String contentUrl, String objectKey) {
    }
}
