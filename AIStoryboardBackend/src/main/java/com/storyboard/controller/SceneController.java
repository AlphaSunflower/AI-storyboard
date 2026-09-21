package com.storyboard.controller;

import com.storyboard.dto.request.ReorderScenesRequest;
import com.storyboard.dto.request.SceneRequest;
import com.storyboard.dto.request.SceneAssetGroupBindingRequest;
import com.storyboard.dto.request.SceneMaterialReferenceRequest;
import com.storyboard.dto.request.SceneMaterialsUpdateRequest;
import com.storyboard.common.ApiResponse;
import com.storyboard.dto.response.SceneReferenceResponse;
import com.storyboard.dto.response.SceneResponse;
import com.storyboard.dto.response.SceneAssetGroupBindingResponse;
import com.storyboard.service.SceneService;
import com.storyboard.service.SceneMaterialService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 分镜端点：新增 / 更新 / 删除 / 排序 / 参考素材（列表由 ProjectController 随项目返回）。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SceneController {

    private final SceneService sceneService;
    private final SceneMaterialService sceneMaterialService;

    @PostMapping("/projects/{projectId}/scenes")
    public ApiResponse<SceneResponse> add(Authentication auth, @PathVariable String projectId, @RequestBody SceneRequest request) {
        return ApiResponse.ok(sceneService.addScene(auth.getName(), projectId, request));
    }

    @PutMapping("/scenes/{id}")
    public ApiResponse<SceneResponse> update(Authentication auth, @PathVariable String id, @RequestBody SceneRequest request) {
        return ApiResponse.ok(sceneService.updateScene(auth.getName(), id, request));
    }

    @DeleteMapping("/scenes/{id}")
    public ApiResponse<Void> delete(Authentication auth, @PathVariable String id) {
        sceneService.deleteScene(auth.getName(), id);
        return ApiResponse.ok("删除成功", null);
    }

    /** 批量重排分镜顺序。 */
    @PutMapping("/projects/{projectId}/scenes/reorder")
    public ApiResponse<Void> reorder(Authentication auth, @PathVariable String projectId, @RequestBody ReorderScenesRequest request) {
        sceneService.reorderScenes(auth.getName(), projectId, request.sceneIds());
        return ApiResponse.ok("排序成功", null);
    }

    /** 参考素材列表（type: image/video/audio）。 */
    @GetMapping("/scenes/{id}/references")
    public ApiResponse<List<SceneReferenceResponse>> listReferences(Authentication auth, @PathVariable String id) {
        return ApiResponse.ok(sceneService.listReferences(auth.getName(), id));
    }

    /** 上传参考素材（multipart：type + file）。 */
    @PostMapping("/scenes/{id}/references")
    public ApiResponse<SceneReferenceResponse> uploadReference(Authentication auth, @PathVariable String id,
            @RequestParam String type, @RequestParam String purpose, @RequestParam MultipartFile file) {
        return ApiResponse.ok(sceneService.uploadReference(auth.getName(), id, type, purpose, file));
    }

    /** 删除参考素材。 */
    @DeleteMapping("/scenes/references/{referenceId}")
    public ApiResponse<Void> deleteReference(Authentication auth, @PathVariable String referenceId) {
        sceneService.deleteReference(auth.getName(), referenceId);
        return ApiResponse.ok("删除成功", null);
    }

    /** 追加产出/分镜图片引用（不覆盖本地上传素材与已关联资产）。 */
    @PostMapping("/scenes/{id}/references/link")
    public ApiResponse<List<SceneReferenceResponse>> linkReferences(
            Authentication auth,
            @PathVariable String id,
            @RequestBody List<SceneMaterialReferenceRequest> references) {
        return ApiResponse.ok(sceneMaterialService.linkReferences(auth.getName(), id, references));
    }

    /** 原子保存分镜素材选择：AI 资产 + 产出/当前分镜图片引用。 */
    @PutMapping("/scenes/{id}/materials")
    public ApiResponse<Void> replaceMaterials(Authentication auth, @PathVariable String id,
                                              @RequestBody SceneMaterialsUpdateRequest request) {
        sceneMaterialService.replaceMaterials(auth.getName(), id, request);
        return ApiResponse.ok("保存成功", null);
    }

    @GetMapping("/scenes/{id}/asset-groups")
    public ApiResponse<List<SceneAssetGroupBindingResponse>> listAssetGroups(
            Authentication auth,
            @PathVariable String id
    ) {
        return ApiResponse.ok(sceneMaterialService.listAssetGroups(auth.getName(), id));
    }

    @PutMapping("/scenes/{id}/asset-groups")
    public ApiResponse<SceneAssetGroupBindingResponse> bindAssetGroup(
            Authentication auth,
            @PathVariable String id,
            @RequestBody SceneAssetGroupBindingRequest request
    ) {
        return ApiResponse.ok(sceneMaterialService.bindAssetGroup(auth.getName(), id, request));
    }

    @DeleteMapping("/scenes/{id}/asset-groups/{groupId}")
    public ApiResponse<Void> removeAssetGroup(
            Authentication auth,
            @PathVariable String id,
            @PathVariable String groupId,
            @RequestParam String purpose
    ) {
        sceneMaterialService.removeAssetGroup(auth.getName(), id, groupId, purpose);
        return ApiResponse.ok("已移除资产组", null);
    }
}
