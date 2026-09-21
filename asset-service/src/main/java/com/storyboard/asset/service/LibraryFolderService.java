package com.storyboard.asset.service;

import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.CreateFolderRequest;
import com.storyboard.asset.client.AssetContracts.CopyFoldersRequest;
import com.storyboard.asset.client.AssetContracts.FolderTreeResponse;
import com.storyboard.asset.client.AssetContracts.LibraryFolderNode;
import com.storyboard.asset.client.AssetContracts.MoveAssetsToFolderRequest;
import com.storyboard.asset.client.AssetContracts.MoveFolderRequest;
import com.storyboard.asset.client.AssetContracts.UpdateFolderRequest;

import java.util.List;

public interface LibraryFolderService {

    LibraryFolderNode create(String userId, CreateFolderRequest request);

    LibraryFolderNode rename(String userId, String folderId, UpdateFolderRequest request);

    void delete(String userId, String folderId, long expectedVersion);

    FolderTreeResponse tree(String userId, AssetScope scope, String projectId);

    void moveAssets(String userId, MoveAssetsToFolderRequest request);

    void moveFolder(String userId, String folderId, MoveFolderRequest request);

    com.storyboard.asset.client.AssetContracts.BulkAssetOperationResult copyFolders(
            String userId,
            CopyFoldersRequest request
    );

    String ensurePath(String userId, AssetScope scope, String projectId, List<String> segments);
}
