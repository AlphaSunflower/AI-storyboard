package com.storyboard.asset.controller;

import com.storyboard.asset.dto.ImportContracts.ItemResult;
import com.storyboard.asset.dto.ImportContracts.ScanRequest;
import com.storyboard.asset.dto.ImportContracts.ScanResponse;
import com.storyboard.asset.service.ImportService;
import com.storyboard.common.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/library/imports")
public class LibraryImportController {

    private final ImportService importService;

    public LibraryImportController(ImportService importService) {
        this.importService = importService;
    }

    @PostMapping("/scan")
    public ApiResponse<ScanResponse> scan(Authentication auth, @RequestBody ScanRequest request) {
        return ApiResponse.ok(importService.scan(auth.getName(), request));
    }

    @PostMapping("/{jobId}/items")
    public ApiResponse<ItemResult> uploadItem(
            Authentication auth,
            @PathVariable String jobId,
            @RequestParam String itemKey,
            @RequestParam String confirmationToken,
            @RequestPart("file") MultipartFile file
    ) {
        return ApiResponse.ok(importService.uploadItem(
                auth.getName(), jobId, itemKey, confirmationToken, file));
    }
}
