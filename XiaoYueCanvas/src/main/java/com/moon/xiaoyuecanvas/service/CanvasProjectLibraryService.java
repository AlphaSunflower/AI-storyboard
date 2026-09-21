package com.moon.xiaoyuecanvas.service;

import com.moon.xiaoyuecanvas.dto.response.CanvasProjectLibraryResponse;
import com.moon.xiaoyuecanvas.entity.CanvasAsset;
import com.storyboard.asset.client.AssetContracts.AssetScope;

import java.util.List;

public interface CanvasProjectLibraryService {

    CanvasProjectLibraryResponse load(
            String userId,
            String projectId,
            AssetScope scope,
            String folderId,
            String type
    );

    CanvasAsset importAsset(String userId, String canvasId, String libraryAssetId, String producerNodeId);

    List<CanvasAsset> importGroup(String userId, String canvasId, String groupAssetId, String producerNodeId);
}
