package com.storyboard.controller;

import com.storyboard.dto.request.GenerateImageRequest;
import com.storyboard.dto.request.GenerateScriptRequest;
import com.storyboard.dto.request.GenerateVideoRequest;
import com.storyboard.dto.response.ApiResponse;
import com.storyboard.dto.response.TaskStatusResponse;
import com.storyboard.service.ai.GatewayModelService;
import com.storyboard.service.ai.ImageGenerationService;
import com.storyboard.service.ai.ScriptGenerationService;
import com.storyboard.service.ai.VideoGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 生成端点：分镜脚本 / 图片 / 视频生成 + 任务轮询 + 网关模型列表。
 * 仅收参 → 调 Service → 封装返回，不持有任何业务逻辑。
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AIController {

    private final ScriptGenerationService scriptService;
    private final ImageGenerationService imageService;
    private final VideoGenerationService videoService;
    private final GatewayModelService gatewayModelService;

    @PostMapping("/generate-script")
    public ApiResponse<Map<String, Object>> generateScript(@RequestBody GenerateScriptRequest request) {
        // 生成 + 落库全部在 Service 层完成（原 Controller 内 sceneMapper 循环写库已下沉）
        return ApiResponse.ok(scriptService.generateAndSaveScenes(
            request.projectId(), request.scriptText(), request.creationType(),
            request.customTypeDesc(), request.aspectRatio(), request.model(),
            request.understandingModel(), request.referenceImages()
        ));
    }

    @PostMapping("/generate-image")
    public ApiResponse<Map<String, String>> generateImage(@RequestBody GenerateImageRequest request) {
        String imageUrl = imageService.generateImage(
            request.sceneId(), request.prompt(), request.model(),
            request.size(), request.quality(), request.aspectRatio(),
            request.referenceImages(),
            request.mode(), request.generatedImageUrl(),
            request.n()
        );
        return ApiResponse.ok(Map.of("imageUrl", imageUrl, "sceneId", request.sceneId()));
    }

    @PostMapping("/generate-video")
    public ApiResponse<Map<String, String>> generateVideo(@RequestBody GenerateVideoRequest request) {
        String taskId = videoService.createVideoTask(
            request.sceneId(), request.prompt(), request.model(),
            request.resolution(), request.size(), request.aspectRatio(),
            request.duration(), request.negativePrompt(), request.seed(),
            request.referenceImages(), request.generatedImageUrl(),
            request.referenceVideos(), request.referenceAudios()
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

    /** GET /api/ai/models：从 LLM 网关拉取生图/生视频模型列表（网关按路由 type 过滤），供前端模型下拉动态展示。
     *  网关不可用/无路由时返回空数组，前端回退硬编码默认列表。 */
    @GetMapping("/models")
    public ApiResponse<Map<String, Object>> aiModels() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imageModels", gatewayModelService.fetchModels("image"));
        result.put("videoModels", gatewayModelService.fetchModels("video"));
        result.put("understandingModels", gatewayModelService.fetchModels("vision"));
        return ApiResponse.ok(result);
    }
}
