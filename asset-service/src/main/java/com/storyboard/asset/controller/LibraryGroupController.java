package com.storyboard.asset.controller;

import com.storyboard.asset.client.AssetContracts.AssetGroupSnapshot;
import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.CreateGroupRequest;
import com.storyboard.asset.client.AssetContracts.CreateGroupFromAssetsRequest;
import com.storyboard.asset.client.AssetContracts.ReplaceGroupItemsRequest;
import com.storyboard.asset.client.AssetContracts.SnapshotRequest;
import com.storyboard.asset.client.AssetContracts.GenerationOutputEvent;
import com.storyboard.asset.dto.AssetGroupDetailResponse;
import com.storyboard.asset.service.AssetCatalogService;
import com.storyboard.asset.service.GroupService;
import com.storyboard.asset.service.GenerationOutputService;
import com.storyboard.common.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/library")
public class LibraryGroupController {

    private final GroupService groupService;
    private final AssetCatalogService catalogService;
    private final GenerationOutputService generationOutputService;

    public LibraryGroupController(
            GroupService groupService,
            AssetCatalogService catalogService,
            GenerationOutputService generationOutputService
    ) {
        this.groupService = groupService;
        this.catalogService = catalogService;
        this.generationOutputService = generationOutputService;
    }

    @PostMapping("/groups")
    public ApiResponse<AssetSummary> create(
            Authentication auth,
            @RequestBody CreateGroupRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        String id = groupService.create(auth.getName(), request, idempotencyKey);
        return ApiResponse.ok(catalogService.get(auth.getName(), id).asset());
    }

    @GetMapping("/groups/{id}")
    public ApiResponse<AssetGroupDetailResponse> getGroup(Authentication auth, @PathVariable String id) {
        return ApiResponse.ok(groupService.get(auth.getName(), id));
    }

    @PostMapping("/groups/from-assets")
    public ApiResponse<AssetSummary> createFromAssets(
            Authentication auth,
            @RequestBody CreateGroupFromAssetsRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(groupService.createFromAssets(auth.getName(), request, idempotencyKey));
    }

    @PutMapping("/groups/{id}/items")
    public ApiResponse<Void> replaceItems(
            Authentication auth,
            @PathVariable String id,
            @RequestParam long expectedVersion,
            @RequestBody ReplaceGroupItemsRequest request
    ) {
        groupService.replaceItems(auth.getName(), id, request, expectedVersion);
        return ApiResponse.ok("资产组已更新", null);
    }

    @PostMapping("/groups/{id}/snapshots")
    public ApiResponse<AssetGroupSnapshot> snapshot(
            Authentication auth,
            @PathVariable String id,
            @RequestBody(required = false) SnapshotRequest request
    ) {
        return ApiResponse.ok(groupService.createSnapshot(auth.getName(), id, request));
    }

    @GetMapping("/snapshots/{id}")
    public ApiResponse<AssetGroupSnapshot> getSnapshot(
            Authentication auth,
            @PathVariable String id,
            @RequestParam(required = false) String actorUserId
    ) {
        String userId = resolveActorUserId(auth, actorUserId);
        return ApiResponse.ok(groupService.getSnapshot(userId, id));
    }

    @PostMapping("/generation-outputs")
    public ApiResponse<AssetSummary> registerGenerationOutput(
            Authentication auth,
            @RequestBody GenerationOutputEvent event
    ) {
        GenerationOutputEvent scoped = new GenerationOutputEvent(
                event.producer() == null ? "moon-agent" : event.producer(),
                auth.getName(), event.projectId(), event.generationRecordId(), event.resultIndex(),
                event.groupId(), event.resultAssetId(), event.resultUrl(), event.mediaType(),
                event.prompt(), event.model(), event.metadata());
        return ApiResponse.ok(generationOutputService.register(scoped));
    }

    private static String resolveActorUserId(Authentication auth, String actorUserId) {
        String authenticatedUser = auth.getName();
        if (actorUserId == null || actorUserId.isBlank() || actorUserId.equals(authenticatedUser)) {
            return authenticatedUser;
        }
        boolean internal = auth.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_INTERNAL".equals(authority.getAuthority()));
        if (!internal) {
            throw new com.storyboard.common.BusinessException(40301, "无权以其他用户身份读取快照");
        }
        return actorUserId;
    }
}
