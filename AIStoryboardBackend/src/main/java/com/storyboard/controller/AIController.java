package com.storyboard.controller;

import com.storyboard.dto.request.GenerateImageRequest;
import com.storyboard.dto.request.GenerateScriptRequest;
import com.storyboard.dto.request.GenerateVideoRequest;
import com.storyboard.dto.response.ApiResponse;
import com.storyboard.dto.response.TaskStatusResponse;
import com.storyboard.entity.Scene;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.service.ProjectService;
import com.storyboard.service.ai.AiConfigProperties;
import com.storyboard.service.ai.ImageGenerationService;
import com.storyboard.service.ai.ScriptGenerationService;
import com.storyboard.service.ai.VideoGenerationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private final AiConfigProperties config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public AIController(ScriptGenerationService scriptService, ImageGenerationService imageService,
                        VideoGenerationService videoService, SceneMapper sceneMapper,
                        ProjectService projectService, AiConfigProperties config) {
        this.scriptService = scriptService;
        this.imageService = imageService;
        this.videoService = videoService;
        this.sceneMapper = sceneMapper;
        this.projectService = projectService;
        this.config = config;
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
            request.size(), request.quality(), request.aspectRatio(),
            request.referenceImages(),
            request.mode(), request.generatedImageUrl()
        );
        return ApiResponse.ok(Map.of("imageUrl", imageUrl, "sceneId", request.sceneId()));
    }

    @PostMapping("/generate-video")
    public ApiResponse<Map<String, String>> generateVideo(@RequestBody GenerateVideoRequest request) {
        String taskId = videoService.createVideoTask(
            request.sceneId(), request.prompt(), request.model(),
            request.resolution(), request.size(), request.aspectRatio(),
            request.duration(), request.negativePrompt(), request.seed(),
            request.referenceImages(), request.generatedImageUrl()
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
        result.put("imageModels", fetchGatewayModels("image"));
        result.put("videoModels", fetchGatewayModels("video"));
        return ApiResponse.ok(result);
    }

    /** 拉取网关模型列表：GET {gateway}/v1/models?type=X → data[].id；失败返回空列表（不阻塞主流程） */
    private List<Map<String, String>> fetchGatewayModels(String type) {
        List<Map<String, String>> models = new ArrayList<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getGatewayBaseUrl() + "/v1/models?type=" + type))
                    .header("Authorization", "Bearer " + config.getGatewayApiKey())
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return models;
            }
            JsonNode data = objectMapper.readTree(resp.body()).path("data");
            if (data.isArray()) {
                for (JsonNode n : data) {
                    String id = n.path("id").asText("");
                    if (!id.isBlank()) {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("value", id);
                        m.put("label", id);
                        models.add(m);
                    }
                }
            }
        } catch (Exception e) {
            // 网关不可达/解析失败：返回空列表，前端用默认模型继续工作
        }
        return models;
    }
}
