package com.storyboard.asset.controller;

import com.storyboard.asset.client.AssetContracts.AssetSummary;
import com.storyboard.asset.client.AssetContracts.GenerationOutputEvent;
import com.storyboard.asset.service.GenerationOutputService;
import com.storyboard.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/library")
public class InternalLibraryController {

    private final GenerationOutputService generationOutputService;

    public InternalLibraryController(GenerationOutputService generationOutputService) {
        this.generationOutputService = generationOutputService;
    }

    @PostMapping("/generation-outputs")
    public ApiResponse<AssetSummary> registerGenerationOutput(@RequestBody GenerationOutputEvent event) {
        return ApiResponse.ok(generationOutputService.register(event));
    }
}
