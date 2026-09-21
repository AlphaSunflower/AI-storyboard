package com.storyboard.asset.controller;

import com.storyboard.asset.client.AssetContracts.AssetDetail;
import com.storyboard.asset.client.AssetContracts.AssetScope;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.AssignTagsRequest;
import com.storyboard.asset.client.AssetContracts.BulkAssetIdsRequest;
import com.storyboard.asset.client.AssetContracts.BulkAssetOperationResult;
import com.storyboard.asset.client.AssetContracts.CreateAssetRequest;
import com.storyboard.asset.client.AssetContracts.DuplicateAssetsRequest;
import com.storyboard.asset.client.AssetContracts.LinkProjectRequest;
import com.storyboard.asset.client.AssetContracts.PageResponse;
import com.storyboard.asset.client.AssetContracts.PromoteRequest;
import com.storyboard.asset.client.AssetContracts.TagMode;
import com.storyboard.asset.client.AssetContracts.UpdateAssetRequest;
import com.storyboard.asset.service.AssetCatalogService;
import com.storyboard.asset.service.TagService;
import com.storyboard.asset.service.MediaUploadService;
import com.storyboard.asset.dto.ImportContracts.AnalysisTaskStatus;
import com.storyboard.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/library/assets")
public class LibraryAssetController {

    private final AssetCatalogService catalogService;
    private final TagService tagService;
    private final MediaUploadService uploadService;

    public LibraryAssetController(
            AssetCatalogService catalogService,
            TagService tagService,
            MediaUploadService uploadService
    ) {
        this.catalogService = catalogService;
        this.tagService = tagService;
        this.uploadService = uploadService;
    }

    @GetMapping
    public ApiResponse<PageResponse<AssetSummary>> list(
            Authentication auth,
            @RequestParam(required = false) AssetScope scope,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String folderId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) AssetType type,
            @RequestParam(required = false) List<String> tagIds,
            @RequestParam(required = false, defaultValue = "ALL") TagMode tagMode,
            @RequestParam(defaultValue = "false") boolean deleted,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "40") int size
    ) {
        return ApiResponse.ok(catalogService.list(
                auth.getName(), scope, projectId, folderId, q, type, tagIds, tagMode, deleted, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<AssetDetail> get(Authentication auth, @PathVariable String id) {
        return ApiResponse.ok(catalogService.get(auth.getName(), id));
    }

    @GetMapping("/analysis-tasks")
    public ApiResponse<List<AnalysisTaskStatus>> analysisTasks(
            Authentication auth,
            @RequestParam(required = false) List<String> assetIds
    ) {
        return ApiResponse.ok(catalogService.listAnalysisTaskStatuses(auth.getName(), assetIds));
    }

    @PostMapping
    public ApiResponse<AssetSummary> create(
            Authentication auth,
            @Valid @RequestBody CreateAssetRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(catalogService.create(auth.getName(), request, idempotencyKey));
    }

    @PutMapping("/{id}")
    public ApiResponse<AssetSummary> update(
            Authentication auth,
            @PathVariable String id,
            @Valid @RequestBody UpdateAssetRequest request
    ) {
        return ApiResponse.ok(catalogService.update(auth.getName(), id, request));
    }

    @PostMapping("/{id}/promote")
    public ApiResponse<AssetSummary> promote(
            Authentication auth,
            @PathVariable String id,
            @RequestBody PromoteRequest request
    ) {
        return ApiResponse.ok(catalogService.promote(auth.getName(), id, request.expectedVersion()));
    }

    @PostMapping("/bulk/trash")
    public ApiResponse<BulkAssetOperationResult> bulkTrash(
            Authentication auth,
            @RequestBody BulkAssetIdsRequest request
    ) {
        return ApiResponse.ok(catalogService.bulkTrash(auth.getName(), request.assetIds()));
    }

    @PostMapping("/bulk/promote")
    public ApiResponse<BulkAssetOperationResult> bulkPromote(
            Authentication auth,
            @RequestBody BulkAssetIdsRequest request
    ) {
        return ApiResponse.ok(catalogService.bulkPromote(auth.getName(), request.assetIds()));
    }

    @PostMapping("/bulk/duplicate")
    public ApiResponse<BulkAssetOperationResult> duplicate(
            Authentication auth,
            @RequestBody DuplicateAssetsRequest request
    ) {
        return ApiResponse.ok(catalogService.duplicate(auth.getName(), request));
    }

    @PostMapping("/{id}/projects")
    public ApiResponse<Void> linkProject(
            Authentication auth,
            @PathVariable String id,
            @RequestBody LinkProjectRequest request
    ) {
        catalogService.linkProject(auth.getName(), id, request.projectId());
        return ApiResponse.ok("已关联项目", null);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<AssetSummary> trash(
            Authentication auth,
            @PathVariable String id,
            @RequestParam long expectedVersion
    ) {
        return ApiResponse.ok(catalogService.trash(auth.getName(), id, expectedVersion));
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<AssetSummary> restore(Authentication auth, @PathVariable String id) {
        return ApiResponse.ok(catalogService.restore(auth.getName(), id));
    }

    @DeleteMapping("/{id}/purge")
    public ApiResponse<Void> purge(Authentication auth, @PathVariable String id) {
        catalogService.purge(auth.getName(), id);
        return ApiResponse.ok("已进入清理队列", null);
    }

    @PutMapping("/{id}/tags")
    public ApiResponse<Void> replaceTags(
            Authentication auth,
            @PathVariable String id,
            @RequestBody AssignTagsRequest request
    ) {
        tagService.replaceAssetTags(auth.getName(), id, request);
        return ApiResponse.ok("标签已更新", null);
    }

    @PostMapping("/{id}/media")
    public ApiResponse<com.storyboard.asset.client.AssetContracts.MediaRef> addMedia(
            Authentication auth,
            @PathVariable String id,
            @RequestParam(required = false) String constraintText,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file
    ) {
        return ApiResponse.ok(uploadService.uploadToAsset(auth.getName(), id, file, constraintText));
    }

    @DeleteMapping("/{id}/media/{mediaId}")
    public ApiResponse<Void> deleteMedia(
            Authentication auth,
            @PathVariable String id,
            @PathVariable String mediaId
    ) {
        uploadService.deleteAssetMedia(auth.getName(), id, mediaId);
        return ApiResponse.ok("媒体已删除", null);
    }
}
