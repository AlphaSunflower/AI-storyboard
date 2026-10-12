package com.storyboard.asset.controller;

import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.CreateFolderRequest;
import com.storyboard.asset.client.AssetContracts.CopyFoldersRequest;
import com.storyboard.asset.client.AssetContracts.FolderTreeResponse;
import com.storyboard.asset.client.AssetContracts.LibraryFolderNode;
import com.storyboard.asset.client.AssetContracts.MoveAssetsToFolderRequest;
import com.storyboard.asset.client.AssetContracts.MoveFolderRequest;
import com.storyboard.asset.client.AssetContracts.UpdateFolderRequest;
import com.storyboard.asset.dto.ImportContracts.AnalysisRequestResult;
import com.storyboard.asset.service.AssetCatalogService;
import com.storyboard.asset.service.LibraryFolderService;
import com.storyboard.common.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/library/folders")
public class LibraryFolderController {

    private final LibraryFolderService folderService;
    private final AssetCatalogService catalogService;

    public LibraryFolderController(LibraryFolderService folderService, AssetCatalogService catalogService) {
        this.folderService = folderService;
        this.catalogService = catalogService;
    }

    @GetMapping("/tree")
    public ApiResponse<FolderTreeResponse> tree(
            Authentication auth,
            @RequestParam(required = false) AssetScope scope,
            @RequestParam(required = false) String projectId
    ) {
        return ApiResponse.ok(folderService.tree(auth.getName(), scope, projectId));
    }

    @PostMapping
    public ApiResponse<LibraryFolderNode> create(
            Authentication auth,
            @RequestBody CreateFolderRequest request
    ) {
        return ApiResponse.ok(folderService.create(auth.getName(), request));
    }

    @PutMapping("/{id}")
    public ApiResponse<LibraryFolderNode> rename(
            Authentication auth,
            @PathVariable String id,
            @RequestBody UpdateFolderRequest request
    ) {
        return ApiResponse.ok(folderService.rename(auth.getName(), id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            Authentication auth,
            @PathVariable String id,
            @RequestParam long expectedVersion
    ) {
        folderService.delete(auth.getName(), id, expectedVersion);
        return ApiResponse.ok("目录已删除", null);
    }

    @PutMapping("/{id}/assets")
    public ApiResponse<Void> moveAssets(
            Authentication auth,
            @PathVariable String id,
            @RequestBody MoveAssetsToFolderRequest request
    ) {
        folderService.moveAssets(auth.getName(), new MoveAssetsToFolderRequest(
                auth.getName(),
                request.scope(),
                request.ownerProjectId(),
                "UNFILED".equals(id) ? null : id,
                request.assetIds()
        ));
        return ApiResponse.ok("素材已移动", null);
    }

    @PutMapping("/{id}/parent")
    public ApiResponse<Void> moveFolder(
            Authentication auth,
            @PathVariable String id,
            @RequestBody MoveFolderRequest request
    ) {
        folderService.moveFolder(auth.getName(), id, request);
        return ApiResponse.ok("文件夹已移动", null);
    }

    @PostMapping("/bulk/copy")
    public ApiResponse<com.storyboard.asset.client.AssetContracts.BulkAssetOperationResult> copyFolders(
            Authentication auth,
            @RequestBody CopyFoldersRequest request
    ) {
        return ApiResponse.ok(folderService.copyFolders(auth.getName(), request));
    }

    /** 手动触发：为目录（含子目录）内的图片素材排队 AI 元数据完善。 */
    @PostMapping("/{id}/analysis")
    public ApiResponse<AnalysisRequestResult> requestAnalysis(
            Authentication auth,
            @PathVariable String id,
            @RequestParam(required = false) AssetScope scope,
            @RequestParam(required = false) String projectId
    ) {
        return ApiResponse.ok(catalogService.requestFolderAnalysis(auth.getName(), scope, projectId, id));
    }
}
