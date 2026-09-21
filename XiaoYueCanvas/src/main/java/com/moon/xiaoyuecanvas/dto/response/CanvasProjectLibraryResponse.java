package com.moon.xiaoyuecanvas.dto.response;

import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.FolderTreeResponse;

import java.util.List;

public record CanvasProjectLibraryResponse(
        FolderTreeResponse projectTree,
        FolderTreeResponse globalTree,
        List<AssetSummary> assets,
        List<AssetSummary> groups
) {
}
