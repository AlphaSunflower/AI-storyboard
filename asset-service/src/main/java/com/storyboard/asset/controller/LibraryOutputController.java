package com.storyboard.asset.controller;

import com.storyboard.asset.client.AssetContracts.AssetType;
import com.storyboard.asset.client.AssetContracts.OutputConversationSummary;
import com.storyboard.asset.client.AssetContracts.TagMode;
import com.storyboard.asset.service.AssetCatalogService;
import com.storyboard.common.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 产出素材的对话维度视图：一个对话一个虚拟文件夹。 */
@RestController
@RequestMapping("/api/library/outputs")
public class LibraryOutputController {

    private final AssetCatalogService catalogService;

    public LibraryOutputController(AssetCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/conversations")
    public ApiResponse<List<OutputConversationSummary>> conversations(
            Authentication auth,
            @RequestParam String projectId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) AssetType type,
            @RequestParam(required = false) List<String> tagIds,
            @RequestParam(required = false, defaultValue = "ALL") TagMode tagMode,
            @RequestParam(required = false) Integer minRating,
            @RequestParam(required = false) String flag,
            @RequestParam(required = false) String colorLabel
    ) {
        return ApiResponse.ok(catalogService.listOutputConversations(
                auth.getName(), projectId, q, type, tagIds, tagMode, minRating, flag, colorLabel));
    }
}
