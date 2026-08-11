package com.storyboard.controller;

import com.storyboard.dto.request.SceneRequest;
import com.storyboard.dto.response.ApiResponse;
import com.storyboard.dto.response.SceneResponse;
import com.storyboard.service.SceneService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 分镜端点：新增 / 更新 / 删除（列表由 ProjectController 随项目返回）。
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
}
