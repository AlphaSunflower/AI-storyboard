package com.storyboard.controller;

import com.storyboard.dto.response.ApiResponse;
import com.storyboard.dto.response.SceneResponse;
import com.storyboard.service.SceneService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class SceneController {

    private final SceneService sceneService;

    public SceneController(SceneService sceneService) {
        this.sceneService = sceneService;
    }

    @PostMapping("/projects/{projectId}/scenes")
    public ApiResponse<SceneResponse> add(@PathVariable String projectId, @RequestBody Map<String, Object> data) {
        return ApiResponse.ok(sceneService.addScene(projectId, data));
    }

    @PutMapping("/scenes/{id}")
    public ApiResponse<SceneResponse> update(@PathVariable String id, @RequestBody Map<String, Object> data) {
        return ApiResponse.ok(sceneService.updateScene(id, data));
    }

    @DeleteMapping("/scenes/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        sceneService.deleteScene(id);
        return ApiResponse.ok("删除成功", null);
    }
}
