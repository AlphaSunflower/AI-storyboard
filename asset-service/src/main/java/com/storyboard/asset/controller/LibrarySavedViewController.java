package com.storyboard.asset.controller;

import com.storyboard.asset.client.AssetContracts.LibrarySavedView;
import com.storyboard.asset.client.AssetContracts.SaveViewRequest;
import com.storyboard.asset.client.AssetContracts.UpdateSavedViewRequest;
import com.storyboard.asset.service.SavedViewService;
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

import java.util.List;

/** 项目库保存视图：命名筛选条件，一键恢复。 */
@RestController
@RequestMapping("/api/library/views")
public class LibrarySavedViewController {

    private final SavedViewService savedViewService;

    public LibrarySavedViewController(SavedViewService savedViewService) {
        this.savedViewService = savedViewService;
    }

    @GetMapping
    public ApiResponse<List<LibrarySavedView>> list(
            Authentication auth,
            @RequestParam String projectId
    ) {
        return ApiResponse.ok(savedViewService.list(auth.getName(), projectId));
    }

    @PostMapping
    public ApiResponse<LibrarySavedView> create(
            Authentication auth,
            @RequestBody SaveViewRequest request
    ) {
        return ApiResponse.ok(savedViewService.create(auth.getName(), request));
    }

    @PutMapping("/{id}")
    public ApiResponse<LibrarySavedView> update(
            Authentication auth,
            @PathVariable String id,
            @RequestBody UpdateSavedViewRequest request
    ) {
        return ApiResponse.ok(savedViewService.update(auth.getName(), id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(Authentication auth, @PathVariable String id) {
        savedViewService.delete(auth.getName(), id);
        return ApiResponse.ok("视图已删除", null);
    }
}
