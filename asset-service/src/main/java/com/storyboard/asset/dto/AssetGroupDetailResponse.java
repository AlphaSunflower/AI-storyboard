package com.storyboard.asset.dto;

import com.storyboard.asset.client.AssetContracts.MediaRef;
import com.storyboard.asset.client.AssetContracts.TagRef;

import java.util.List;

public record AssetGroupDetailResponse(
        String groupId,
        String name,
        String description,
        long version,
        List<AssetGroupMediaItem> media
) {
    public record AssetGroupMediaItem(
            MediaRef media,
            String assetId,
            String assetName,
            String assetDescription,
            long assetVersion,
            List<TagRef> assetTags
    ) {
    }
}
