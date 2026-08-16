package com.storyboard.controller;

import com.storyboard.dto.request.AssetCreateRequest;
import com.storyboard.dto.request.AssetUpdateRequest;
import com.storyboard.dto.request.SceneAssetsUpdateRequest;
import com.storyboard.dto.response.ApiResponse;
import com.storyboard.dto.response.AssetImageVO;
import com.storyboard.dto.response.AssetVO;
import com.storyboard.service.AssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * AI 资产库端点：资产 CRUD + 图片上传 + 分镜关联。
 * 仅收参 → 调 Service → 封装返回，无业务逻辑。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;

    @PostMapping("/assets")
    public ApiResponse<AssetVO> create(Authentication auth, @RequestBody AssetCreateRequest request) {
        return ApiResponse.ok(assetService.create(auth.getName(), request));
    }

    @GetMapping("/assets")
    public ApiResponse<List<AssetVO>> list(Authentication auth,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String type) {
        return ApiResponse.ok(assetService.list(auth.getName(), projectId, type));
    }

    @PutMapping("/assets/{id}")
    public ApiResponse<AssetVO> update(Authentication auth, @PathVariable String id,
            @RequestBody AssetUpdateRequest request) {
        return ApiResponse.ok(assetService.update(auth.getName(), id, request));
    }

    @DeleteMapping("/assets/{id}")
    public ApiResponse<Void> delete(Authentication auth, @PathVariable String id) {
        assetService.delete(auth.getName(), id);
        return ApiResponse.ok("删除成功", null);
    }

    @PostMapping("/assets/{id}/images")
    public ApiResponse<AssetImageVO> uploadImage(Authentication auth, @PathVariable String id,
            @RequestParam MultipartFile file) {
        return ApiResponse.ok(assetService.uploadImage(auth.getName(), id, file));
    }

    @DeleteMapping("/assets/{id}/images/{imageId}")
    public ApiResponse<Void> deleteImage(Authentication auth, @PathVariable String id,
            @PathVariable String imageId) {
        assetService.deleteImage(auth.getName(), id, imageId);
        return ApiResponse.ok("删除成功", null);
    }

    @PutMapping("/scenes/{id}/assets")
    public ApiResponse<Void> setSceneAssets(Authentication auth, @PathVariable String id,
            @RequestBody SceneAssetsUpdateRequest request) {
        assetService.setSceneAssets(auth.getName(), id, request.assetIds());
        return ApiResponse.ok("关联成功", null);
    }

    @GetMapping("/scenes/{id}/assets")
    public ApiResponse<List<AssetVO>> listSceneAssets(Authentication auth, @PathVariable String id) {
        return ApiResponse.ok(assetService.listSceneAssets(auth.getName(), id));
    }
}
