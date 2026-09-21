package com.storyboard.asset.controller;

import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.AssetDetail;
import com.storyboard.asset.client.AssetContracts.ExternalMediaRegistration;
import com.storyboard.asset.client.AssetContracts.GenerationOutputEvent;
import com.storyboard.asset.service.AssetCatalogService;
import com.storyboard.asset.service.ExternalMediaService;
import com.storyboard.asset.service.LegacyAiAssetMigrationService;
import com.storyboard.asset.service.GenerationOutputService;
import com.storyboard.common.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/library")
public class InternalLibraryController {

    private final GenerationOutputService generationOutputService;
    private final ExternalMediaService externalMediaService;
    private final AssetCatalogService catalogService;
    private final LegacyAiAssetMigrationService legacyAiAssetMigrationService;

    public InternalLibraryController(
            GenerationOutputService generationOutputService,
            ExternalMediaService externalMediaService,
            AssetCatalogService catalogService,
            LegacyAiAssetMigrationService legacyAiAssetMigrationService
    ) {
        this.generationOutputService = generationOutputService;
        this.externalMediaService = externalMediaService;
        this.catalogService = catalogService;
        this.legacyAiAssetMigrationService = legacyAiAssetMigrationService;
    }

    @PostMapping("/generation-outputs")
    public ApiResponse<AssetSummary> registerGenerationOutput(@RequestBody GenerationOutputEvent event) {
        return ApiResponse.ok(generationOutputService.register(event));
    }

    @PostMapping("/external-media")
    public ApiResponse<AssetSummary> registerExternalMedia(
            Authentication auth,
            @RequestBody ExternalMediaRegistration request
    ) {
        return ApiResponse.ok(externalMediaService.register(auth.getName(), request));
    }

    @GetMapping("/assets/by-source")
    public ApiResponse<AssetDetail> findBySource(
            Authentication auth,
            @RequestParam String ownerUserId,
            @RequestParam String sourceType,
            @RequestParam String sourceId
    ) {
        if (!auth.getName().equals(ownerUserId)) {
            throw new com.storyboard.common.BusinessException(40301, "无权读取其他用户的媒体");
        }
        return ApiResponse.ok(catalogService.findBySource(auth.getName(), sourceType, sourceId));
    }

    @PostMapping("/migrations/legacy-ai-assets")
    public ApiResponse<Integer> migrateLegacyAiAssets(Authentication auth) {
        return ApiResponse.ok(legacyAiAssetMigrationService.migrate(auth.getName()));
    }
}
