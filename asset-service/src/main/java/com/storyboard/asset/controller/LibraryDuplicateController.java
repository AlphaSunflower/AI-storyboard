package com.storyboard.asset.controller;

import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.DuplicateGroup;
import com.storyboard.asset.client.AssetContracts.MergeDuplicatesRequest;
import com.storyboard.asset.client.AssetContracts.PageResponse;
import com.storyboard.asset.service.DuplicateService;
import com.storyboard.common.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 项目库重复素材：同一 sha256 被多个素材引用时的检测与合并。 */
@RestController
@RequestMapping("/api/library/duplicates")
public class LibraryDuplicateController {

    private final DuplicateService duplicateService;

    public LibraryDuplicateController(DuplicateService duplicateService) {
        this.duplicateService = duplicateService;
    }

    @GetMapping
    public ApiResponse<PageResponse<DuplicateGroup>> list(
            Authentication auth,
            @RequestParam String projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(duplicateService.list(auth.getName(), projectId, page, size));
    }

    @PostMapping("/merge")
    public ApiResponse<AssetSummary> merge(
            Authentication auth,
            @RequestBody MergeDuplicatesRequest request
    ) {
        return ApiResponse.ok(duplicateService.merge(auth.getName(), request));
    }
}
