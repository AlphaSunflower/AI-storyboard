package com.storyboard.controller;

import com.storyboard.dto.request.SceneRequest;
import com.storyboard.common.ApiResponse;
import com.storyboard.dto.response.SceneReferenceResponse;
import com.storyboard.dto.response.SceneResponse;
import com.storyboard.service.SceneService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 分镜端点：新增 / 更新 / 删除 / 参考素材（列表由 ProjectController 随项目返回）。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SceneController {

    private final SceneService sceneService;

    @PostMapping("/projects/{projectId}/scenes")
    public ApiResponse<SceneResponse> add(@PathVariable String projectId, @RequestBody SceneRequest request) {
        return ApiResponse.ok(sceneService.addScene(projectId, request));
    }

    @PutMapping("/scenes/{id}")
    public ApiResponse<SceneResponse> update(@PathVariable String id, @RequestBody SceneRequest request) {
        return ApiResponse.ok(sceneService.updateScene(id, request));
    }

    @DeleteMapping("/scenes/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        sceneService.deleteScene(id);
        return ApiResponse.ok("删除成功", null);
    }

    /** 参考素材列表（type: image/video/audio）。 */
    @GetMapping("/scenes/{id}/references")
    public ApiResponse<List<SceneReferenceResponse>> listReferences(@PathVariable String id) {
        return ApiResponse.ok(sceneService.listReferences(id));
    }

    /** 上传参考素材（multipart：type + file）。 */
    @PostMapping("/scenes/{id}/references")
    public ApiResponse<SceneReferenceResponse> uploadReference(@PathVariable String id,
            @RequestParam String type, @RequestParam String purpose, @RequestParam MultipartFile file) {
        return ApiResponse.ok(sceneService.uploadReference(id, type, purpose, file));
    }

    /** 删除参考素材。 */
    @DeleteMapping("/scenes/references/{referenceId}")
    public ApiResponse<Void> deleteReference(@PathVariable String referenceId) {
        sceneService.deleteReference(referenceId);
        return ApiResponse.ok("删除成功", null);
    }
}
