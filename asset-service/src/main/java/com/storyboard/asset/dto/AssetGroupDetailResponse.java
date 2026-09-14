package com.storyboard.asset.dto;

import com.storyboard.asset.client.AssetContracts.MediaRef;

import java.util.List;

public record AssetGroupDetailResponse(
        String groupId,
        String name,
        String description,
        long version,
        List<MediaRef> media
) {
}
