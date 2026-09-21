package com.storyboard.asset.service;

import com.storyboard.asset.client.AssetContracts.AssetGroupSnapshot;
import com.storyboard.asset.client.AssetContracts.CreateGroupRequest;
import com.storyboard.asset.client.AssetContracts.CreateGroupFromAssetsRequest;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.ReplaceGroupItemsRequest;
import com.storyboard.asset.client.AssetContracts.SnapshotRequest;

public interface GroupService {

    AssetGroupSnapshot createSnapshot(String userId, String groupId, SnapshotRequest request);

    AssetGroupSnapshot getSnapshot(String userId, String snapshotId);

    com.storyboard.asset.dto.AssetGroupDetailResponse get(String userId, String groupId);

    void replaceItems(String userId, String groupId, ReplaceGroupItemsRequest request, long expectedVersion);

    String create(String userId, CreateGroupRequest request, String idempotencyKey);

    AssetSummary createFromAssets(
            String userId,
            CreateGroupFromAssetsRequest request,
            String idempotencyKey
    );
}
