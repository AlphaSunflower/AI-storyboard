package com.storyboard.controller;

import com.storyboard.dto.request.GenerateImageRequest;
import com.storyboard.dto.request.GenerateScriptRequest;
import com.storyboard.dto.request.GenerateVideoRequest;
import com.storyboard.dto.response.ApiResponse;
import com.storyboard.dto.response.TaskStatusResponse;
import com.storyboard.entity.Scene;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.service.ProjectService;
import com.storyboard.service.ai.ImageGenerationService;
import com.storyboard.service.ai.ScriptGenerationService;
import com.storyboard.service.ai.VideoGenerationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AIController {

    private final ScriptGenerationService scriptService;
    private final ImageGenerationService imageService;
    private final VideoGenerationService videoService;
    private final SceneMapper sceneMapper;
    private final ProjectService projectService;

    public AIController(ScriptGenerationService scriptService, ImageGenerationService imageService,
                        VideoGenerationService videoService, SceneMapper sceneMapper,
                        ProjectService projectService) {
        this.scriptService = scriptService;
        this.imageService = imageService;
        this.videoService = videoService;
        this.sceneMapper = sceneMapper;
        this.projectService = projectService;
    }

    @PostMapping("/generate-script")
    public ApiResponse<Map<String, Object>> generateScript(@RequestBody GenerateScriptRequest request) {
        List<Map<String, Object>> scenes = scriptService.generateScenes(
            request.projectId(), request.scriptText(), request.creationType(),
            request.customTypeDesc(), request.aspectRatio(), request.model()
        );

        // 将生成的 scenes 存入数据库
        for (Map<String, Object> s : scenes) {
            Scene scene = new Scene();
            scene.setProjectId(request.projectId());
            scene.setSceneNumber((Integer) s.get("sceneNumber"));
            scene.setScriptContent((String) s.get("scriptContent"));
            scene.setImagePrompt((String) s.get("imagePrompt"));
            scene.setVideoPrompt((String) s.get("videoPrompt"));
            scene.setNegativePrompt((String) s.get("negativePrompt"));
            scene.setCameraMovement((String) s.get("cameraMovement"));
            scene.setShotType((String) s.get("shotType"));
            scene.setSoundDesign((String) s.get("soundDesign"));
            sceneMapper.insert(scene);
        }

        return ApiResponse.ok(Map.of("projectId", request.projectId(), "sceneCount", scenes.size()));
    }

    @PostMapping("/generate-image")
    public ApiResponse<Map<String, String>> generateImage(@RequestBody GenerateImageRequest request) {
        String imageUrl = imageService.generateImage(
            request.sceneId(), request.prompt(), request.model(),
            request.size(), request.aspectRatio(), request.referenceImages()
        );
        return ApiResponse.ok(Map.of("imageUrl", imageUrl, "sceneId", request.sceneId()));
    }

    @PostMapping("/generate-video")
    public ApiResponse<Map<String, String>> generateVideo(@RequestBody GenerateVideoRequest request) {
        String taskId = videoService.createVideoTask(
            request.sceneId(), request.prompt(), request.model(),
            request.resolution(), request.duration(), request.referenceImages()
        );
        return ApiResponse.ok(Map.of("taskId", taskId, "sceneId", request.sceneId()));
    }

    @GetMapping("/task/{taskId}")
    public ApiResponse<TaskStatusResponse> getTaskStatus(@PathVariable String taskId) {
        Map<String, String> result = videoService.pollVideoTask(taskId);
        return ApiResponse.ok(new TaskStatusResponse(
            result.get("taskId"), result.get("status"),
            result.get("videoUrl"), result.get("progress"), result.get("error")
        ));
    }
}
